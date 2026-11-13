package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Historia skanów (config/rynek-analiza-history.json). Źródła punktów:
 *  - SRC_ACTIVE: pełna analiza (/ah search, sortowanie po najniższej cenie) - to są "skany" na wykresie,
 *  - SRC_PASSIVE_TRUSTED: okno rynku otwarte przez BK, na którym widać prawdziwe minimum (jedna strona albo strona 1 posortowana rosnąco);
 *    pokazywane na wykresie tylko na życzenie,
 *  - SRC_PASSIVE_SAMPLE: stary rodzaj punktu z poprzedniej wersji (oferty z okien BK przy innym sortowaniu) - nie zapisujemy ich już i nie pokazujemy.
 */
public final class PriceHistory {
    public static final byte SRC_ACTIVE = 0;
    public static final byte SRC_PASSIVE_TRUSTED = 1;
    public static final byte SRC_PASSIVE_SAMPLE = 2;

    public static boolean ENABLED = true;
    public static int PASSIVE_INTERVAL_SEC = 60;
    public static int MAX_POINTS = 1000;
    private static final long SAME_VALUE_MIN_GAP_MS = 10 * 60 * 1000L;

    private static final Gson GSON = new Gson();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rynek-analiza-history-io");
        t.setDaemon(true);
        return t;
    });

    /** Jeden pomiar: czas, najtańsza oferta za sztukę, średnia 3 najtańszych, liczba ofert, źródło. */
    public static final class Point {
        public long t;
        public double p1;
        public double a3;
        public int n;
        public byte s;

        public Point() {}

        public Point(long t, double p1, double a3, int n, byte s) {
            this.t = t;
            this.p1 = p1;
            this.a3 = a3;
            this.n = n;
            this.s = s;
        }

        public boolean trusted() { return s != SRC_PASSIVE_SAMPLE; }
    }

    /** Statystyki średniej 3 najtańszych (a3) z podanych skanów. */
    public static final class Stats {
        public int count;
        public double mean, min, max, last;
        public double prev = Double.NaN;      // średnia z poprzedniego skanu (NaN, gdy jest tylko jeden)
        public double cheapest = Double.NaN;  // najtańsza pojedyncza oferta ze wszystkich skanów
        public long lastT;

        public double spread() { return max - min; }

        /** Zmiana ostatniego skanu względem poprzedniego w %; NaN, gdy nie ma poprzedniego. */
        public double changePct() { return prev > 0 ? (last - prev) / prev * 100.0 : Double.NaN; }
    }

    private final Map<String, List<Point>> data = new LinkedHashMap<>();
    private final Map<String, Long> lastRecorded = new HashMap<>();
    private boolean dirty;

    public PriceHistory() {}

    // ---------- zapis pomiarów ----------
    public void record(String key, long t, double p1, double a3, int n, byte src) {
        if (!ENABLED || key == null || key.isEmpty() || !(p1 > 0) || !(a3 > 0)) return;
        if (src == SRC_PASSIVE_SAMPLE) return; // próbek nie zapisujemy (to nie jest minimum rynku)
        List<Point> l = data.computeIfAbsent(key, k -> new ArrayList<>());
        if (src != SRC_ACTIVE) {
            // obserwacja: nie częściej niż co PASSIVE_INTERVAL_SEC i nie dublujemy tej samej wartości w krótkim czasie.
            // Skany z analizy zapisujemy zawsze - każdy to osobny punkt z własnym czasem.
            String lk = key + "#" + src;
            Long last = lastRecorded.get(lk);
            if (last != null && t - last < PASSIVE_INTERVAL_SEC * 1000L) return;
            for (int i = l.size() - 1; i >= 0; i--) {
                Point q = l.get(i);
                if (q.s != src) continue;
                if (Math.abs(q.p1 - p1) < 0.005 && Math.abs(q.a3 - a3) < 0.005 && t - q.t < SAME_VALUE_MIN_GAP_MS) return;
                break;
            }
            lastRecorded.put(lk, t);
        }
        l.add(new Point(t, p1, a3, n, src));
        dirty = true;
        thin(l);
    }

    /**
     * Gdy punktów jest za dużo: najpierw znika co druga obserwacja (skany z analizy zostają, dopóki są obserwacje do usunięcia),
     * a gdy zostały same skany - co drugi skan ze starszej połowy (najstarszy, czyli pierwsza analiza, zostaje zawsze).
     * Autopilot skanuje wszystko co kilkanaście minut (~100 punktów na dobę), więc dawna historia z czasem się przerzedza,
     * a ostatnie dni zostają w pełnej rozdzielczości.
     */
    private static void thin(List<Point> l) {
        while (l.size() > MAX_POINTS) {
            List<Point> out = new ArrayList<>();
            int passive = 0;
            for (Point p : l) {
                if (p.s != SRC_ACTIVE && passive++ % 2 == 1) continue;
                out.add(p);
            }
            if (out.size() == l.size()) {
                out.clear();
                int half = l.size() / 2;
                for (int i = 0; i < half; i++) if (i % 2 == 0) out.add(l.get(i));
                out.addAll(l.subList(half, l.size()));
                if (out.size() >= l.size()) return; // za mało punktów, żeby cokolwiek usunąć
            }
            l.clear();
            l.addAll(out);
        }
    }

    // ---------- odczyt ----------
    public int count(String key) {
        List<Point> l = data.get(key);
        return l == null ? 0 : l.size();
    }

    /**
     * Ostatnie skany przedmiotu (od najstarszego do najnowszego), najwyżej limit sztuk.
     * Domyślnie tylko skany z analizy; z includeObserved także zaufane pomiary z obserwacji rynku podczas pracy BK.
     */
    public List<Point> lastScans(String key, boolean includeObserved, int limit) {
        List<Point> l = data.get(key);
        if (l == null) return List.of();
        List<Point> sel = new ArrayList<>();
        for (Point p : l) {
            if (p.s == SRC_ACTIVE || (includeObserved && p.s == SRC_PASSIVE_TRUSTED)) sel.add(p);
        }
        sel.sort(Comparator.comparingLong(p -> p.t));
        return new ArrayList<>(sel.subList(Math.max(0, sel.size() - Math.max(1, limit)), sel.size()));
    }

    /** Najnowszy skan z analizy, w którym było co najmniej minOffers ofert; null, gdy nie ma. */
    public Point lastActive(String key, int minOffers) {
        List<Point> l = data.get(key);
        if (l == null) return null;
        Point best = null;
        for (Point p : l) {
            if (p.s == SRC_ACTIVE && p.a3 > 0 && p.n >= minOffers && (best == null || p.t >= best.t)) best = p;
        }
        return best;
    }

    public static Stats stats(List<Point> pts) {
        Stats s = new Stats();
        double sum = 0;
        for (Point p : pts) {
            if (!(p.a3 > 0)) continue;
            if (s.count == 0) {
                s.min = s.max = p.a3;
                s.cheapest = p.p1;
            } else {
                s.prev = s.last;
                s.min = Math.min(s.min, p.a3);
                s.max = Math.max(s.max, p.a3);
                s.cheapest = Math.min(s.cheapest, p.p1);
            }
            s.count++;
            sum += p.a3;
            s.last = p.a3;
            s.lastT = p.t;
        }
        if (s.count > 0) s.mean = sum / s.count;
        return s;
    }

    // ---------- plik ----------
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza-history.json");
    }

    public static PriceHistory load() {
        PriceHistory h = new PriceHistory();
        try {
            Path f = file();
            if (Files.exists(f)) {
                Type type = new TypeToken<LinkedHashMap<String, ArrayList<Point>>>() {}.getType();
                Map<String, ArrayList<Point>> m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), type);
                if (m != null) for (Map.Entry<String, ArrayList<Point>> e : m.entrySet()) h.data.put(e.getKey(), e.getValue());
            }
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się wczytać historii cen: " + e);
        }
        return h;
    }

    /** Zapis synchroniczny (przy zamykaniu gry, gdy wątek w tle mógłby nie zdążyć). */
    public void saveBlocking() {
        if (!dirty) return;
        dirty = false;
        try {
            Path f = file();
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się zapisać historii cen: " + e);
        }
    }

    /** Zapis w tle (JSON budowany od razu, plik zapisywany w osobnym wątku). Nic nie robi, gdy nie ma zmian. */
    public void save() {
        if (!dirty) return;
        dirty = false;
        final String json = GSON.toJson(data);
        IO.execute(() -> {
            try {
                Path f = file();
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                System.err.println("[rynek-analiza] nie udało się zapisać historii cen: " + e);
            }
        });
    }
}
