package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Autopilot cen: co apIntervalMin minut skanuje wszystkie przedmioty z listy BK i po każdym skanie ustala cenę sprzedaży
 * (pod najtańszą cudzą ofertę) i cenę skupu (rynek minus marża z progu ceny - patrz AutoPricing.decideBuy).
 * Przedmiot, który BK kupił apRescanBuys razy od jego ostatniego skanu, jest skanowany ponownie od razu.
 *
 * Tryb próbny (domyślny po włączeniu): wszystko jest liczone, ale NIC nie trafia do BK; zakupy są wirtualne
 * (oferta tańsza od wyliczonego skupu = "tu bym kupił"), a po 2 h sprawdzane, po ile dałoby się je sprzedać.
 * Na żywo: cena sprzedaży jest wpisywana przedmiotom z włączonym w BK "Sprzedawaniem", skup - wszystkim z Auto: TAK
 * (włączenie kupowania w BK zostaje Twoją decyzją).
 * Zabezpieczenia: podłoga ceny, dzienny budżet, limit niesprzedanych zakupów na przedmiot, hamulec strat.
 *
 * Stan: config/rynek-analiza-autopilot.json.
 */
public final class Autopilot {
    static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    static final long HIST_WINDOW = 7 * DAY;
    static final long SIGHTING_TTL = 15 * MIN;
    static final long SEEN_TTL = 12 * HOUR;
    static final long VIRTUAL_HOLD = 2 * HOUR;
    static final long STALE_LOT = 48 * HOUR;
    static final long LEVEL_TTL = DAY;
    static final int LOG_CAP = 40;

    // ---------- stan zapisywany ----------
    /** Zakupiona partia (prawdziwa albo wirtualna z trybu próbnego), koszt za sztukę. */
    public static final class Lot {
        public long t;
        public double costPP;
        public int count;
        public boolean virtual;
        public boolean auto;   // kupione po cenie ustawionej przez autopilota (liczy się do budżetu)
    }

    /** Wpis w dzienniku przedmiotu. */
    public static final class Decision {
        public long t;
        public double sellPP, buyPP, margin; // margin = marża skupu z progu ceny (ułamek)
        public String why = "";
        public boolean trial;   // decyzja z trybu próbnego (nie wpisana do BK)
        public boolean applied; // wpisana do BK
        public boolean problem; // skan się nie udał (ceny bez zmian)
    }

    /** Cudza oferta tańsza od naszej ceny, zauważona w oknie BK. */
    public static final class Sighting {
        public String id;
        public double pp;
        public long t;
    }

    public static final class ItemState {
        public boolean auto = true;
        public double manualMinSell;   // za sztukę, 0 = brak
        public double manualMaxBuy;    // za sztukę, 0 = brak
        public double sellPP, buyPP;   // ostatnie decyzje autopilota (za sztukę)
        public double buyMargin;       // marża skupu z progu ceny przy ostatniej decyzji (ułamek; 0 = jeszcze nie liczona)
        public AutoPricing.SellState sell = new AutoPricing.SellState();
        public long lastScanAt, lastOffersAt, lastBuyAt, buyLiveAt;
        public String lastWhy = "";
        public List<Lot> lots = new ArrayList<>();
        public List<Decision> log = new ArrayList<>();
        public Map<String, Long> seen = new LinkedHashMap<>();
        public List<Sighting> below = new ArrayList<>();
        public List<double[]> sellTotals = new ArrayList<>(); // {czas, cena wystawki} wpisane do BK - do rozpoznania sprzedaży
        public double refPP;           // najtańsza cudza oferta (za sztukę, bez pomyłek) z ostatniego skanu
        public int buysSinceScan;      // zakupy BK (osobne transakcje) od ostatniego skanu
        public boolean rescan;         // po apRescanBuys zakupach: czeka na ponowny skan (od razu, bez czekania na pełny)
    }

    /** Przedmiot w kolejce skanu autopilota i powód. */
    public static final class Pick {
        public static final int RESCAN = 0, NEW = 1, OLD = 2;
        public final BkBridge.BkTarget t;
        public final int kind;
        public final String why;

        Pick(BkBridge.BkTarget t, int kind, String why) {
            this.t = t;
            this.kind = kind;
            this.why = why;
        }
    }

    public static final class DayStats {
        public String day = "";
        public double spentAuto, spentAll, revenue, realized, lossToday, vRealized;
        public int boughtAuto, boughtAll, sold, soldKnown, vBought, vClosed, priceChanges;
    }

    public static final class Data {
        public Map<String, ItemState> items = new LinkedHashMap<>();
        public Map<String, DayStats> days = new LinkedHashMap<>();
        public List<String> pausedByBudget = new ArrayList<>();
        public List<String> pausedByStock = new ArrayList<>();
        public String pausedDay = "";
        public int closedReal, profitableReal;
    }

    /** Oferta zauważona w oknie rynku. */
    public static final class Offer {
        public final double pp;
        public final int count;
        public final boolean own;
        public final String id;

        public Offer(double pp, int count, boolean own, String id) {
            this.pp = pp;
            this.count = count;
            this.own = own;
            this.id = id;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final AnalizaConfig cfg;
    private final Path file;
    Data data = new Data();
    /** Zatwierdzenie zmian w BK (podmieniane w testach). */
    Runnable committer = BkBridge::commit;
    /** Komunikaty do czatu (podmieniane w testach). */
    Consumer<String> chat = m -> MarketAnalyzer.log.accept(m);
    /** Strefa czasowa dnia budżetu. */
    ZoneId zone = ZoneId.systemDefault();

    private boolean dirty;          // zmiany w BK do zatwierdzenia
    private boolean changed;        // zmiany w stanie do zapisu
    private long lastTick, lastSave;
    private int primedSold = -1;
    private double primedRevenue;
    private int cycleChecked, cycleChanges, cycleSellWritten, cycleBuyWritten;

    public Autopilot(AnalizaConfig cfg, Path file) {
        this.cfg = cfg;
        this.file = file;
    }

    public static Autopilot load(AnalizaConfig cfg) {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza-autopilot.json");
        Autopilot a = new Autopilot(cfg, f);
        try {
            if (Files.exists(f)) {
                Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
                if (d != null) a.data = fix(d);
            }
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się wczytać autopilota: " + e);
        }
        return a;
    }

    private static Data fix(Data d) {
        if (d.items == null) d.items = new LinkedHashMap<>();
        if (d.days == null) d.days = new LinkedHashMap<>();
        if (d.pausedByBudget == null) d.pausedByBudget = new ArrayList<>();
        if (d.pausedByStock == null) d.pausedByStock = new ArrayList<>();
        if (d.pausedDay == null) d.pausedDay = "";
        for (ItemState s : d.items.values()) {
            if (s.sell == null) s.sell = new AutoPricing.SellState();
            if (s.lots == null) s.lots = new ArrayList<>();
            if (s.log == null) s.log = new ArrayList<>();
            if (s.seen == null) s.seen = new LinkedHashMap<>();
            if (s.below == null) s.below = new ArrayList<>();
            if (s.sellTotals == null) s.sellTotals = new ArrayList<>();
            if (s.lastWhy == null) s.lastWhy = "";
        }
        return d;
    }

    public void save() {
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            changed = false;
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się zapisać autopilota: " + e);
        }
    }

    // ---------- ustawienia ----------
    public boolean enabled() { return cfg.autopilot; }

    public boolean live() { return cfg.autopilot && !cfg.apTrial; }

    public AutoPricing.Params params() {
        AutoPricing.Params p = new AutoPricing.Params();
        p.tax = cfg.taxFactor > 0 && cfg.taxFactor <= 1 ? cfg.taxFactor : 0.97;
        p.minProfit = Math.max(0, cfg.apMinProfitPct) / 100.0;
        p.dropMinOffers = Math.max(1, cfg.apDropOffers);
        return p;
    }

    /** Marża skupu z progów dla ceny za sztukę (ułamek). */
    public double marginFor(double pp) {
        return AutoPricing.tierMargin(pp, cfg.buyTierUpTo, cfg.buyTierMarginPct);
    }

    /** Marża skupu przedmiotu: z ostatniej decyzji, a bez niej - z progu jego ceny rynku albo sprzedaży. */
    public double buyMargin(ItemState s) {
        if (s.buyMargin > 0) return s.buyMargin;
        return marginFor(s.refPP > 0 ? s.refPP : s.sellPP);
    }

    public ItemState state(String key) {
        return data.items.computeIfAbsent(key, k -> new ItemState());
    }

    /** Stan przedmiotu bez tworzenia nowego (do wyświetlania). */
    public ItemState peek(String key) {
        return data.items.get(key);
    }

    // ---------- dane z historii analiz ----------
    List<Double> recentAnalyses(String key, long now) {
        PriceHistory h = RynekAnalizaMod.history();
        List<Double> out = new ArrayList<>();
        if (h == null) return out;
        for (PriceHistory.Point p : h.lastScans(key, false, Integer.MAX_VALUE)) {
            if (p.t >= now - HIST_WINDOW && p.a3 > 0) out.add(p.a3);
        }
        return out;
    }

    /** Zwykła cena: mediana analiz z 7 dni (0, gdy brak). */
    public double usual(String key, long now) {
        return AutoPricing.median(recentAnalyses(key, now));
    }

    public int histCount(String key, long now) {
        return recentAnalyses(key, now).size();
    }

    double firstAnalysis(String key) {
        PriceHistory h = RynekAnalizaMod.history();
        if (h == null) return 0;
        List<PriceHistory.Point> l = h.lastScans(key, false, Integer.MAX_VALUE);
        return l.isEmpty() ? 0 : l.get(0).a3;
    }

    // ---------- tryb / stan BK dla przedmiotu ----------
    boolean sellLive(BkBridge.BkTarget t, ItemState s) {
        return live() && s.auto && t.sellEnabled();
    }

    /**
     * Czy skup tego przedmiotu trafia do BK: na żywo, przy Auto: TAK - także gdy kupowanie jest w BK wyłączone
     * (cena czeka gotowa, a włączenie kupowania zostaje decyzją gracza).
     */
    boolean buyLive(ItemState s) {
        return live() && s.auto;
    }

    double currentSell(BkBridge.BkTarget t, ItemState s) {
        if (sellLive(t, s)) {
            double total = t.currentSellPrice();
            return total > 0 ? total / Math.max(1, t.sellCount()) : 0;
        }
        return s.sellPP;
    }

    /** Koszt za sztukę otwartych partii bieżącego trybu (prawdziwych na żywo, wirtualnych w próbie); bez partii - skup ustawiony ręcznie. */
    double costBasis(BkBridge.BkTarget t, ItemState s) {
        boolean virt = !live();
        double cost = 0;
        int n = 0;
        for (Lot l : s.lots) {
            if (l.virtual != virt || l.count <= 0) continue;
            cost += l.costPP * l.count;
            n += l.count;
        }
        if (n > 0) return cost / n;
        if (s.buyLiveAt <= 0 || !live()) { // skup ustawia gracz - traktujemy go jako koszt
            double b = t.currentBuyPrice();
            if (b > 0) return b / Math.max(1, t.buyFactor());
        }
        return 0;
    }

    // ---------- pełny skan ----------
    /**
     * Wynik pełnego skanu (posortowanego od najtańszych) dla przedmiotu: decyzja o cenie sprzedaży i skupu.
     * @return opis decyzji do logu; null = autopilot wyłączony albo przedmiot ma wyłączony automat
     */
    public String onScan(BkBridge.BkTarget t, List<Double> othersPP, int ownOffers, long now) {
        if (!enabled()) return null;
        ItemState s = state(t.key());
        if (!s.auto) return null;
        cycleChecked++;
        changed = true;
        s.lastScanAt = now;
        if (!othersPP.isEmpty()) s.lastOffersAt = now;
        expireLevel(s, now);
        AutoPricing.Params p = params();
        int count = Math.max(1, t.sellCount());
        double usual = usual(t.key(), now);
        int hc = histCount(t.key(), now);
        double cur = currentSell(t, s);
        double floor = AutoPricing.floor(costBasis(t, s), usual, hc, s.manualMinSell, firstAnalysis(t.key()), p);
        // zabezpieczenie: gdy nick nie rozpoznał żadnej Twojej oferty, oferty dokładnie po Twoich niedawnych cenach
        // traktujemy jak Twoje (inaczej autopilot mógłby podcinać sam siebie aż do podłogi)
        List<Double> others = othersPP;
        int skipped = 0;
        if (ownOffers == 0 && !s.sellTotals.isEmpty()) {
            others = new ArrayList<>();
            for (double v : othersPP) {
                if (looksOwn(s, v, count, now)) skipped++;
                else others.add(v);
            }
        }
        AutoPricing.SellDecision d = AutoPricing.decideSell(others, true, cur, count, floor, usual, s.sell, p);
        // cena rynku do skupu: najtańsza cudza bez pomyłek; skan zeruje licznik zakupów (ponowny skan załatwiony)
        AutoPricing.Filtered ref = AutoPricing.filterOutliers(others, usual, p.outlierRatio);
        s.refPP = ref.kept.isEmpty() ? 0 : ref.kept.get(0);
        s.buysSinceScan = 0;
        s.rescan = false;
        s.below.clear();
        noteOutliers(t, s, d.outliers, usual, now);
        String why = applySell(t, s, d, cur, now);
        String buyWhy = decideBuy(t, s, s.refPP, usual, true, now);
        s.lastWhy = why + (buyWhy.isEmpty() ? "" : "; " + buyWhy);
        return t.displayName() + ": autopilot" + (live() ? "" : " (próba)") + " - " + s.lastWhy
                + (ownOffers > 0 ? " (Twoich ofert: " + ownOffers + ")" : "")
                + (skipped > 0 ? " (pominięte " + skipped + " ofert po Twoich cenach - pewnie Twoje)" : "");
    }

    /** Cena za sztukę równa którejś z Twoich wystawek z ostatniej doby (gdy rozpoznawanie po nicku nic nie znalazło). */
    boolean looksOwn(ItemState s, double pp, int count, long now) {
        for (double[] x : s.sellTotals) {
            if (now - (long) x[0] > DAY) continue;
            double ours = x[1] / Math.max(1, count);
            if (Math.abs(pp - ours) <= Math.max(0.005, ours * 1e-6)) return true;
        }
        return false;
    }

    private String applySell(BkBridge.BkTarget t, ItemState s, AutoPricing.SellDecision d, double cur, long now) {
        int count = Math.max(1, t.sellCount());
        if (s.sell.levelBeforeDrop > 0 && s.sell.levelBeforeDropAt <= 0) s.sell.levelBeforeDropAt = now;
        if (!d.changed) {
            if (s.sellPP <= 0 && cur > 0) s.sellPP = cur;
            return "sprzedaż: " + d.reason;
        }
        double oldPP = cur > 0 ? cur : s.sellPP;
        s.sellPP = d.pp;
        boolean applied = false;
        if (sellLive(t, s)) {
            double total = Math.round(d.pp * count);
            t.applySellPrice(total);
            s.sellTotals.add(new double[]{now, total});
            while (s.sellTotals.size() > 10) s.sellTotals.remove(0);
            dirty = true;
            applied = true;
        }
        cycleChanges++;
        today(now).priceChanges++;
        String msg = "sprzedaż " + (oldPP > 0 ? AutoPricing.money(oldPP * count) + " -> " : "") + AutoPricing.money(d.pp * count)
                + (count > 1 ? " za " + count + " szt." : "") + " (" + d.reason + ")";
        log(s, now, msg, applied);
        if (applied) {
            cycleSellWritten++;
            announce(t, msg);
        }
        return msg;
    }

    /**
     * Skup po skanie (albo po obniżce z podglądu BK): rynek i wykres minus marża z progu ceny (AutoPricing.decideBuy).
     * @param market     najtańsza prawdziwa cudza oferta za sztukę (0 = brak ofert)
     * @param chart      cena z wykresu (mediana analiz z 7 dni; 0 = brak)
     * @param allowRaise false = tylko obniżka (podgląd okien BK nie widzi całego rynku)
     */
    private String decideBuy(BkBridge.BkTarget t, ItemState s, double market, double chart, boolean allowRaise, long now) {
        boolean live = buyLive(s);
        int factor = Math.max(1, t.buyFactor());
        double cur = live ? t.currentBuyPrice() / factor : s.buyPP;
        AutoPricing.BuyDecision bd = AutoPricing.decideBuy(market, chart, cfg.buyTierUpTo, cfg.buyTierMarginPct, s.sellPP,
                s.manualMaxBuy, cur, params());
        if (bd.ref > 0) s.buyMargin = bd.margin;
        if (!bd.changed || (!allowRaise && bd.pp > cur)) {
            if (s.buyPP <= 0 && cur > 0) s.buyPP = cur;
            if (live && s.buyLiveAt <= 0 && bd.ref > 0 && cur > 0) s.buyLiveAt = now; // skup w BK już taki, jak trzeba
            if (live) pauseIfOverBudget(t, s, now);
            return bd.changed ? "skup bez zmian (podgląd BK tylko obniża)" : bd.reason;
        }
        double old = cur;
        s.buyPP = bd.pp;
        boolean applied = false;
        if (live) {
            applied = t.applyBuyPrice(bd.pp * factor);
            if (applied) {
                dirty = true;
                if (s.buyLiveAt <= 0) s.buyLiveAt = now;
            }
        }
        String msg = bd.head + ": " + (old > 0 ? AutoPricing.money(old) + " -> " : "") + AutoPricing.money(bd.pp)
                + (factor > 1 ? " (" + AutoPricing.money(bd.pp * factor) + " za " + factor + " szt.)" : "") + " (" + bd.desc + ")";
        log(s, now, msg, applied);
        if (applied) {
            cycleBuyWritten++;
            announce(t, msg);
        }
        if (live) pauseIfOverBudget(t, s, now);
        return msg;
    }

    /** Budżet (albo hamulec strat) już wyczerpany: skup przejęty w tej chwili od razu wstrzymany, bez czekania na tick. */
    private void pauseIfOverBudget(BkBridge.BkTarget t, ItemState s, long now) {
        if (s.buyLiveAt <= 0 || data.pausedByBudget.contains(t.key()) || !t.buyEnabled()) return;
        DayStats d = today(now);
        boolean over = d.spentAuto >= cfg.apBudget - 1e-6;
        boolean brake = cfg.apBudget > 0 && d.lossToday >= 0.2 * cfg.apBudget;
        if (!over && !brake) return;
        boolean first = data.pausedByBudget.isEmpty();
        t.setBuyEnabled(false);
        data.pausedByBudget.add(t.key());
        data.pausedDay = day(now);
        dirty = true;
        changed = true;
        if (first) chat.accept(budgetMessage(d, over));
    }

    private String budgetMessage(DayStats d, boolean over) {
        if (cfg.apBudget <= 0) return "Autopilot: budżet = 0 - skup autopilota wstrzymany (ustawia tylko ceny).";
        if (over) return "Autopilot: budżet na dziś wykorzystany (" + AutoPricing.money(d.spentAuto) + " z " + AutoPricing.money(cfg.apBudget) + ") - skup wstrzymany do jutra.";
        return "Autopilot: hamulec strat (dziś " + AutoPricing.money(d.lossToday) + " straty) - skup wstrzymany do jutra.";
    }

    private void noteOutliers(BkBridge.BkTarget t, ItemState s, List<Double> outliers, double usual, long now) {
        for (double o : outliers) {
            String id = "o:" + Math.round(o * 100);
            if (s.seen.putIfAbsent(id, now) != null) continue;
            chat.accept("Autopilot: podejrzanie tania oferta: " + t.displayName() + " za " + AutoPricing.money(o)
                    + "/szt." + (usual > 0 ? " (zwykle ~" + AutoPricing.money(usual) + ")" : "") + " - pomijam ją w cenie");
        }
    }

    private void expireLevel(ItemState s, long now) {
        if (s.sell.levelBeforeDrop > 0 && s.sell.levelBeforeDropAt > 0 && now - s.sell.levelBeforeDropAt > LEVEL_TTL) {
            s.sell.levelBeforeDrop = 0;
            s.sell.levelBeforeDropAt = 0;
        }
    }

    // ---------- podgląd okien BK ----------
    /** Oferty przedmiotu zauważone w oknie rynku otwartym przez BK (dowolne sortowanie). Tylko obniżki i wirtualne zakupy. */
    public void onObserved(BkBridge.BkTarget t, List<Offer> offers, long now) {
        onObserved(t, offers, false, now);
    }

    /**
     * Oferty przedmiotu z okna rynku otwartego przez BK. trusted = okno pokazuje prawdziwe minimum (jedna strona albo
     * strona 1 od najtańszych); podgląd i tak tylko obniża ceny - podwyżki są sprawą pełnych skanów co apIntervalMin.
     */
    public void onObserved(BkBridge.BkTarget t, List<Offer> offers, boolean trusted, long now) {
        if (!enabled()) return;
        ItemState s = state(t.key());
        if (!s.auto) return;
        cleanup(s, now);
        double cur = currentSell(t, s);
        boolean any = false, ownSeen = false;
        for (Offer o : offers) if (o.own) ownSeen = true;
        int cnt = Math.max(1, t.sellCount());
        for (Offer o : offers) {
            if (o.own || o.pp <= 0) continue;
            if (!ownSeen && looksOwn(s, o.pp, cnt, now)) continue; // jak w onScan: pewnie Twoja oferta
            any = true;
            if (cur > 0 && o.pp < cur - 1e-9) {
                boolean known = false;
                for (Sighting x : s.below) if (x.id.equals(o.id)) { known = true; break; }
                if (!known) {
                    Sighting x = new Sighting();
                    x.id = o.id;
                    x.pp = o.pp;
                    x.t = now;
                    s.below.add(x);
                    changed = true;
                }
            }
            if (!live() && s.buyPP > 0 && o.pp <= s.buyPP + 1e-9 && s.seen.putIfAbsent("v:" + o.id, now) == null) {
                virtualBuy(t, s, o.pp, Math.max(1, o.count), now);
            }
        }
        if (any) s.lastOffersAt = now;
        AutoPricing.Params p = params();
        if (cur <= 0 || s.below.size() < p.dropMinOffers) return;
        List<Double> pps = new ArrayList<>();
        for (Sighting x : s.below) pps.add(x.pp);
        double usual = usual(t.key(), now);
        int count = Math.max(1, t.sellCount());
        // duże spadki zostawiamy pełnym skanom (tam jest potwierdzenie w kolejnych skanach)
        AutoPricing.Filtered f = AutoPricing.filterOutliers(pps, usual, p.outlierRatio);
        if (f.kept.isEmpty()) return;
        double target = (Math.ceil(f.kept.get(0) * count - 1e-6) - p.undercut) / count;
        if (target < cur * (1 - p.bigDrop)) return;
        int hc = histCount(t.key(), now);
        double floor = AutoPricing.floor(costBasis(t, s), usual, hc, s.manualMinSell, firstAnalysis(t.key()), p);
        AutoPricing.SellDecision d = AutoPricing.decideSell(pps, false, cur, count, floor, usual, s.sell, p);
        if (!d.changed) return;
        changed = true;
        s.below.clear();
        String why = applySell(t, s, d, cur, now);
        // rynek zszedł niżej: skup za nim (tylko w dół - podgląd nie widzi całego rynku)
        double market = s.refPP > 0 ? Math.min(s.refPP, f.kept.get(0)) : f.kept.get(0);
        String buyWhy = decideBuy(t, s, market, usual, false, now);
        s.lastWhy = why + (buyWhy.isEmpty() ? "" : "; " + buyWhy) + " [podgląd BK]";
    }

    private void cleanup(ItemState s, long now) {
        s.below.removeIf(x -> now - x.t > SIGHTING_TTL);
        for (Iterator<Map.Entry<String, Long>> it = s.seen.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > SEEN_TTL) it.remove();
        }
        while (s.seen.size() > 300) s.seen.remove(s.seen.keySet().iterator().next());
    }

    private void virtualBuy(BkBridge.BkTarget t, ItemState s, double pp, int count, long now) {
        Lot l = new Lot();
        l.t = now;
        l.costPP = pp;
        l.count = count;
        l.virtual = true;
        s.lots.add(l);
        s.lastBuyAt = now;
        DayStats d = today(now);
        d.vBought++;
        changed = true;
        log(s, now, "wirtualny zakup " + count + " szt. po " + AutoPricing.money(pp), false);
    }

    // ---------- zakupy i sprzedaże z BK ----------
    /** Zakup zrobiony przez BK (z jego historii): przypisanie do przedmiotu, budżet, partia do rozliczenia przy sprzedaży. */
    public void onPurchase(List<BkBridge.BkTarget> ts, ItemStack stack, double total, int count, long now) {
        if (!enabled() || total <= 0) return;
        DayStats d = today(now);
        d.boughtAll++;
        d.spentAll += total;
        changed = true;
        BkBridge.BkTarget t = null;
        for (BkBridge.BkTarget x : ts) {
            try {
                if (x.matches(stack)) { t = x; break; }
            } catch (Throwable ignored) {}
        }
        if (t == null) return;
        ItemState s = state(t.key());
        Lot l = new Lot();
        l.t = now;
        l.count = Math.max(1, count);
        l.costPP = total / l.count;
        l.auto = live() && s.auto && s.buyLiveAt > 0;
        s.lots.add(l);
        s.lastBuyAt = now;
        if (l.auto) {
            d.boughtAuto++;
            d.spentAuto += total;
        }
        log(s, now, "kupione przez BK: " + l.count + " szt. za " + AutoPricing.money(total) + (l.auto ? " (skup autopilota)" : ""), false);
        if (s.auto) {
            // kilka zakupów tego samego przedmiotu od ostatniego skanu: rynek mógł się zmienić - skan od razu
            s.buysSinceScan++;
            int need = cfg.apRescanBuys;
            if (need > 0 && s.buysSinceScan >= need && !s.rescan) {
                s.rescan = true;
                String m = zakupy(s.buysSinceScan) + " od ostatniego skanu - ponowny skan";
                log(s, now, m, false);
                chat.accept("Autopilot: " + t.displayName() + ": " + m + ".");
            }
        }
        enforceLimits(ts, now);
    }

    /** "1 zakup", "3 zakupy", "5 zakupów", "22 zakupy". */
    static String zakupy(int n) {
        int d = n % 10, h = n % 100;
        String w = n == 1 ? "zakup" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "zakupy" : "zakupów";
        return n + " " + w;
    }

    /** "po 1 zakupie", "po 3 zakupach". */
    static String poZakupach(int n) {
        return n == 1 ? "po 1 zakupie" : "po " + n + " zakupach";
    }

    /** Liczniki sprzedaży z BK (liczba sprzedanych i suma przychodu); różnica = nowe sprzedaże. */
    public void onSalesCounters(List<BkBridge.BkTarget> ts, int sold, double revenue, long now) {
        if (primedSold < 0 || sold < primedSold || revenue < primedRevenue - 1e-6) {
            primedSold = sold;
            primedRevenue = revenue;
            return;
        }
        int k = sold - primedSold;
        double amount = revenue - primedRevenue;
        primedSold = sold;
        primedRevenue = revenue;
        if (k <= 0) return;
        if (k == 1) {
            onSale(ts, amount, now);
        } else {
            DayStats d = today(now);
            d.sold += k;
            d.revenue += Math.max(0, amount);
            changed = true;
        }
    }

    /**
     * Sprzedaż jednej wystawki (kwota z komunikatu serwera; zwykle po podatku). Przedmiot rozpoznajemy po cenie
     * (jak robi to BK): cena wystawki x podatek albo sama cena wystawki.
     */
    public void onSale(List<BkBridge.BkTarget> ts, double amount, long now) {
        if (!enabled()) return;
        DayStats d = today(now);
        d.sold++;
        d.revenue += Math.max(0, amount);
        changed = true;
        if (amount <= 0) return;
        double tax = params().tax;
        BkBridge.BkTarget best = null;
        boolean bestNet = true;
        double bestErr = Double.MAX_VALUE;
        for (BkBridge.BkTarget t : ts) {
            ItemState s = data.items.get(t.key());
            List<Double> prices = new ArrayList<>();
            if (t.currentSellPrice() > 0) prices.add(t.currentSellPrice());
            if (s != null) for (double[] x : s.sellTotals) if (now - (long) x[0] < DAY) prices.add(x[1]);
            for (double pr : prices) {
                double tol = Math.max(2, pr * 0.005);
                double eNet = Math.abs(amount - pr * tax), eGross = Math.abs(amount - pr);
                if (eNet <= tol && eNet < bestErr) { best = t; bestErr = eNet; bestNet = true; }
                if (eGross <= tol && eGross < bestErr) { best = t; bestErr = eGross; bestNet = false; }
            }
        }
        if (best == null) return;
        d.soldKnown++;
        ItemState s = state(best.key());
        int count = Math.max(1, best.sellCount());
        double net = bestNet ? amount : amount * tax;
        closeRealLots(best, s, count, net, now);
        enforceLimits(ts, now);
    }

    private void closeRealLots(BkBridge.BkTarget t, ItemState s, int pieces, double net, long now) {
        int left = pieces;
        double cost = 0;
        int covered = 0;
        for (Iterator<Lot> it = s.lots.iterator(); it.hasNext() && left > 0; ) {
            Lot l = it.next();
            if (l.virtual) continue;
            int take = Math.min(left, l.count);
            cost += take * l.costPP;
            covered += take;
            l.count -= take;
            left -= take;
            if (l.count <= 0) it.remove();
        }
        if (covered == 0) {
            log(s, now, "sprzedane: " + AutoPricing.money(net) + " (bez zakupu w księdze)", false);
            return;
        }
        double netCovered = net * covered / pieces;
        double realized = netCovered - cost;
        DayStats d = today(now);
        d.realized += realized;
        if (realized < 0) d.lossToday += -realized;
        data.closedReal++;
        if (realized > 0) data.profitableReal++;
        log(s, now, "sprzedane: " + covered + " szt., zysk " + (realized >= 0 ? "+" : "") + AutoPricing.money(realized), false);
    }

    // ---------- okresowo: rozliczenia wirtualne, limity, nowy dzień ----------
    public void tick(List<BkBridge.BkTarget> ts, long now) {
        if (!enabled()) return;
        if (now - lastTick < 30_000) return;
        lastTick = now;
        String today = day(now);
        if (!data.pausedByBudget.isEmpty() && !today.equals(data.pausedDay)) {
            int n = unpause(ts, data.pausedByBudget);
            if (n > 0) chat.accept("Autopilot: nowy dzień - skup wznowiony (" + n + " przedm.).");
        }
        for (BkBridge.BkTarget t : ts) {
            ItemState s = data.items.get(t.key());
            if (s == null || !s.auto) continue;
            settle(t, s, now);
        }
        enforceLimits(ts, now);
        while (data.days.size() > 31) data.days.remove(data.days.keySet().iterator().next());
        if (changed && now - lastSave > 60_000) {
            lastSave = now;
            save();
        }
    }

    /** Rozliczenia: wirtualne zakupy po 2 h (ile dałoby się zarobić), prawdziwe sprzed 48 h bez sprzedaży - z magazynu. */
    void settle(BkBridge.BkTarget t, ItemState s, long now) {
        double tax = params().tax;
        for (Iterator<Lot> it = s.lots.iterator(); it.hasNext(); ) {
            Lot l = it.next();
            if (l.virtual) {
                if (now - l.t < VIRTUAL_HOLD) continue;
                double value = s.sellPP > 0 ? s.sellPP : usual(t.key(), now);
                it.remove();
                changed = true;
                if (value <= 0) continue;
                DayStats d = today(now);
                d.vClosed++;
                d.vRealized += (value * tax - l.costPP) * l.count;
                log(s, now, "wirtualna sprzedaż " + l.count + " szt.: " + (value * tax >= l.costPP ? "+" : "")
                        + AutoPricing.money((value * tax - l.costPP) * l.count), false);
            } else if (now - l.t >= STALE_LOT) {
                it.remove();
                changed = true;
                log(s, now, "zakup sprzed 48 h bez rozpoznanej sprzedaży - usunięty z magazynu", false);
            }
        }
    }

    /** Budżet, hamulec strat i limit niesprzedanych zakupów (tylko na żywo). */
    void enforceLimits(List<BkBridge.BkTarget> ts, long now) {
        if (!live()) return;
        DayStats d = today(now);
        String today = day(now);
        // budżet 0 = autopilot ustawia ceny, ale nic nie kupuje (bezpieczniej niż "bez limitu")
        boolean over = d.spentAuto >= cfg.apBudget - 1e-6;
        boolean brake = cfg.apBudget > 0 && d.lossToday >= 0.2 * cfg.apBudget;
        if (over || brake) {
            int n = 0;
            for (BkBridge.BkTarget t : ts) {
                ItemState s = data.items.get(t.key());
                if (s == null || !s.auto || s.buyLiveAt <= 0 || data.pausedByBudget.contains(t.key())) continue;
                if (!t.buyEnabled()) continue;
                t.setBuyEnabled(false);
                data.pausedByBudget.add(t.key());
                n++;
            }
            if (n > 0) {
                data.pausedDay = today;
                dirty = true;
                changed = true;
                chat.accept(budgetMessage(d, over));
            }
        } else if (!data.pausedByBudget.isEmpty() && today.equals(data.pausedDay)) {
            int n = unpause(ts, data.pausedByBudget); // budżet podniesiony w ustawieniach
            if (n > 0) chat.accept("Autopilot: budżet podniesiony - skup wznowiony.");
        }
        for (BkBridge.BkTarget t : ts) {
            ItemState s = data.items.get(t.key());
            if (s == null) continue;
            int open = 0;
            for (Lot l : s.lots) if (!l.virtual) open++;
            boolean paused = data.pausedByStock.contains(t.key());
            if (!paused && s.auto && s.buyLiveAt > 0 && open >= Math.max(1, cfg.apMaxStock) && t.buyEnabled()) {
                t.setBuyEnabled(false);
                data.pausedByStock.add(t.key());
                dirty = true;
                changed = true;
                chat.accept("Autopilot: " + t.displayName() + ": " + open + " niesprzedane zakupy - skup wstrzymany, aż coś zejdzie.");
            } else if (paused && open < Math.max(1, cfg.apMaxStock)) {
                data.pausedByStock.remove(t.key());
                if (!data.pausedByBudget.contains(t.key()) && !t.buyEnabled()) {
                    t.setBuyEnabled(true);
                    dirty = true;
                }
                changed = true;
            }
        }
    }

    private int unpause(List<BkBridge.BkTarget> ts, List<String> keys) {
        int n = 0;
        for (BkBridge.BkTarget t : ts) {
            if (!keys.contains(t.key())) continue;
            if (!data.pausedByStock.contains(t.key()) && !t.buyEnabled()) {
                t.setBuyEnabled(true);
                n++;
            }
        }
        keys.clear();
        dirty = true;
        changed = true;
        return n;
    }

    /** Zmiana stanu z panelu (np. ręczne minimum) - zapis przy najbliższej okazji. */
    public void markChanged() {
        changed = true;
    }

    /** Zatwierdza zmiany w BK (jeden zapis na wiele zmian). */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        try { committer.run(); } catch (Throwable t) { t.printStackTrace(); }
    }

    /** Wyłączenie autopilota: wstrzymany przez niego skup wraca. */
    public void releasePauses(List<BkBridge.BkTarget> ts) {
        boolean any = !data.pausedByBudget.isEmpty() || !data.pausedByStock.isEmpty();
        if (!any) return;
        List<String> all = new ArrayList<>(data.pausedByBudget);
        all.addAll(data.pausedByStock);
        data.pausedByStock.clear();
        unpause(ts, all);
        data.pausedByBudget.clear();
        flush();
        save();
    }

    // ---------- harmonogram ----------
    /**
     * Kolejność skanu autopilota: najpierw przedmioty czekające na ponowny skan (po apRescanBuys zakupach), potem
     * jeszcze niesprawdzane, a potem wszystkie pozostałe - najdawniej sprawdzane najpierw. Pełny skan bierze całą listę
     * (wszystkie przedmioty z BK, także z Auto: NIE - ich ceny zostają, ale wykres dostaje punkt), ponowny - tylko RESCAN.
     */
    public List<Pick> plan(List<BkBridge.BkTarget> ts, long now) {
        List<Pick> rescanL = new ArrayList<>(), fresh = new ArrayList<>(), rest = new ArrayList<>();
        for (BkBridge.BkTarget t : ts) {
            ItemState s = data.items.get(t.key());
            if (s != null && s.auto && s.rescan) rescanL.add(new Pick(t, Pick.RESCAN, poZakupach(s.buysSinceScan)));
            else if (s == null || s.lastScanAt <= 0) fresh.add(new Pick(t, Pick.NEW, "jeszcze niesprawdzany"));
            else rest.add(new Pick(t, Pick.OLD, ""));
        }
        rest.sort(Comparator.comparingLong(pk -> data.items.get(pk.t.key()).lastScanAt));
        List<Pick> out = new ArrayList<>(rescanL);
        out.addAll(fresh);
        out.addAll(rest);
        return out;
    }

    private boolean cycleFull;

    /** Początek skanu autopilota; full = skan wszystkich przedmiotów (false = ponowny skan po zakupach). */
    public void beginCycle(boolean full) {
        cycleFull = full;
        cycleChecked = 0;
        cycleChanges = 0;
        cycleSellWritten = 0;
        cycleBuyWritten = 0;
    }

    /**
     * Zmiana ceny wpisana do BK - na czat. Podczas skanu wszystkich (co kilkanaście minut, dziesiątki przedmiotów) tylko
     * w dzienniku, a na koniec skanu jedno podsumowanie - żeby czat nie zalewał się co skan.
     */
    private void announce(BkBridge.BkTarget t, String msg) {
        if (cycleFull && MarketAnalyzer.isCycle()) return;
        chat.accept("Autopilot: " + t.displayName() + ": " + msg);
    }

    /** Koniec skanu autopilota: krótkie podsumowanie (próba: zawsze; na żywo: skan wszystkich ze zmianami), zapis stanu. */
    public void endCycle() {
        if (enabled() && !live() && cycleChecked > 0) {
            chat.accept("Autopilot (próba): " + (cycleFull ? "skan wszystkich - sprawdzone " : "ponowny skan po zakupach - sprawdzone ")
                    + cycleChecked + " przedm., zmiany cen: " + cycleChanges + " (nie wpisane do BK - szczegóły w zakładce Autopilot)");
        } else if (enabled() && cycleFull && cycleSellWritten + cycleBuyWritten > 0) {
            chat.accept("Autopilot: skan wszystkich (" + cycleChecked + " przedm.) - wpisane do BK: sprzedaż " + cycleSellWritten
                    + ", skup " + cycleBuyWritten + " (szczegóły w zakładce Autopilot)");
        }
        cycleFull = false;
        save();
    }

    /** Komunikaty cichego skanu autopilota nie idą na czat - trafiają do logu gry (latest.log). */
    void scanLine(String line) {
        System.out.println("[rynek-analiza] autopilot: " + line);
    }

    /** Skan przedmiotu się nie udał (np. rynek się nie otworzył): wpis w dzienniku, ceny bez zmian. */
    public void onScanFailed(BkBridge.BkTarget t, String reason, long now) {
        if (!enabled()) return;
        ItemState s = state(t.key());
        if (!s.auto) return;
        s.lastScanAt = now; // następny skan zacznie od innych przedmiotów
        s.buysSinceScan = 0; // ponowny skan był (nieudany) - bez zapętlania
        s.rescan = false;
        s.lastWhy = "skan nieudany: " + reason;
        log(s, now, s.lastWhy, false);
        s.log.get(s.log.size() - 1).problem = true;
    }

    // ---------- dziennik i statystyki ----------
    private void log(ItemState s, long now, String why, boolean applied) {
        Decision d = new Decision();
        d.t = now;
        d.sellPP = s.sellPP;
        d.buyPP = s.buyPP;
        d.margin = s.buyMargin;
        d.why = why;
        d.trial = !live();
        d.applied = applied;
        s.log.add(d);
        while (s.log.size() > LOG_CAP) s.log.remove(0);
        changed = true;
    }

    String day(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString();
    }

    public DayStats today(long now) {
        String d = day(now);
        DayStats s = data.days.get(d);
        if (s == null) {
            s = new DayStats();
            s.day = d;
            data.days.put(d, s);
        }
        return s;
    }

    /** Ostatnie wpisy ze wszystkich przedmiotów (najnowsze na początku): {czas, nazwa, opis, wpisana, próba, problem}. */
    public List<String[]> recentLog(int max, Map<String, String> names) {
        List<Object[]> all = new ArrayList<>();
        for (Map.Entry<String, ItemState> e : data.items.entrySet()) {
            for (Decision d : e.getValue().log) all.add(new Object[]{d, e.getKey()});
        }
        all.sort((a, b) -> Long.compare(((Decision) b[0]).t, ((Decision) a[0]).t));
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, all.size()); i++) {
            Decision d = (Decision) all.get(i)[0];
            String key = (String) all.get(i)[1];
            out.add(new String[]{String.valueOf(d.t), names.getOrDefault(key, key), d.why, d.applied ? "1" : "0", d.trial ? "1" : "0", d.problem ? "1" : "0"});
        }
        return out;
    }

    public int openLots(ItemState s, boolean virtual) {
        int n = 0;
        for (Lot l : s.lots) if (l.virtual == virtual) n++;
        return n;
    }
}
