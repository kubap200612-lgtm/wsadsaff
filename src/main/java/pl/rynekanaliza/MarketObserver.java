package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Obserwacja rynku bez klikania: gdy BK otworzy okno rynku, czytamy je (tylko czytamy).
 *  - do historii (wykresów) trafiają tylko pomiary zaufane (widać prawdziwe minimum cen); okna przy innym sortowaniu
 *    (np. "Najnowsze") nie pokazują minimum rynku,
 *  - autopilot dostaje oferty z KAŻDEGO okna: nowa cudza oferta tańsza od naszej ceny = ktoś nas podciął (szybka obniżka),
 *    a w trybie próbnym oferta tańsza od wyliczonego skupu = wirtualny zakup.
 * Działa tylko, gdy nasza własna analiza nie trwa. Własne oferty (nick gracza w lore) są pomijane.
 * Co ostatnio odczytała, widać na znaczku "Rynek Analiza: działa" (StatusBadge).
 */
public final class MarketObserver {
    /** Co obserwacja ostatnio zrobiła (do znaczka "działa"). */
    static final class Status {
        long lastReadAt;      // ostatni odczyt okna rynku
        int reads;            // odczytane okna od startu gry
        int priced;           // ofert z ceną w ostatnim oknie
        int matchedItems;     // przedmiotów z listy BK w ostatnim oknie
        int matchedOffers;    // ich cudzych ofert
        int ownOffers;        // Twoich ofert w ostatnim oknie
        String item = "";     // nazwa przedmiotu (gdy w oknie był jeden z listy)
        double cheapestPP;    // najtańsza cudza oferta tego przedmiotu (za sztukę)
        boolean trusted;      // okno od najtańszych (jedna strona albo strona 1 posortowana rosnąco)
        boolean recorded;     // zapisane do historii (wykresów)
        boolean autopilot;    // przekazane autopilotowi
        String error = "";    // ostatni błąd odczytu
        long errorAt;
    }

    static final Status status = new Status();

    private static int lastSig, stable, lastObservedSig, saveTicker;
    private static long lastPass;
    private static List<BkBridge.BkTarget> targets = List.of();
    private static long targetsVersion = Long.MIN_VALUE;
    private static long targetsBuiltAt;

    private MarketObserver() {}

    public static void tick(MinecraftClient mc) {
        PriceHistory hist = RynekAnalizaMod.history();
        AnalizaConfig cfg = RynekAnalizaMod.config();
        if (hist == null || cfg == null) return;
        if (++saveTicker >= 6000) { // co ok. 5 minut (zapis tylko, gdy są zmiany)
            saveTicker = 0;
            hist.save();
        }
        Autopilot ap = RynekAnalizaMod.autopilot();
        boolean apOn = ap != null && ap.enabled();
        boolean histOn = cfg.historyEnabled && cfg.passiveObserve;
        if ((!histOn && !apOn) || MarketAnalyzer.isRunning()) { stable = 0; return; }
        if (!(mc.currentScreen instanceof HandledScreen<?> hs) || !MarketAnalyzer.isMarket(hs) || !MarketAnalyzer.ready(hs)) {
            stable = 0;
            lastSig = 0;
            return;
        }
        if (!BkBridge.available()) return;

        int sig = MarketAnalyzer.signature(hs);
        if (sig != lastSig) { lastSig = sig; stable = 0; return; }
        if (stable < 1000) stable++;
        if (stable < 3 || sig == lastObservedSig) return;
        long now = System.currentTimeMillis();
        if (now - lastPass < 3000) return;
        lastObservedSig = sig;
        lastPass = now;
        try {
            ensureTargets(cfg);
            observeWith(targets, hs, now, hist, histOn, apOn ? ap : null);
        } catch (Throwable t) {
            noteError(t, now); // obserwacja nigdy nie może przeszkadzać botowi BK - błąd widać tylko na znaczku
        }
    }

    static void noteError(Throwable t, long now) {
        status.error = t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
        status.errorAt = now;
    }

    /** Czy obserwacja w ogóle działa (historia z obserwacją albo autopilot). */
    static boolean active(AnalizaConfig cfg, Autopilot ap) {
        return (cfg.historyEnabled && cfg.passiveObserve) || (ap != null && ap.enabled());
    }

    /** Czy otwarte teraz okno rynku zostało już odczytane (ten sam widok co przy ostatnim odczycie). */
    static boolean currentWindowRead() {
        return lastSig != 0 && lastSig == lastObservedSig;
    }

    static void observeWith(List<BkBridge.BkTarget> ts, HandledScreen<?> hs, long now, PriceHistory hist, boolean histOn, Autopilot ap) {
        ScreenHandler h = hs.getScreenHandler();
        int end = Math.max(0, MarketAnalyzer.containerSize(h) - 9); // ostatni rząd = przyciski
        int[] pg = MarketAnalyzer.pageInfo(hs);

        double[] total = new double[end];
        boolean[] own = new boolean[end];
        int priced = 0, ownCount = 0;
        for (int i = 0; i < end; i++) {
            ItemStack st = h.slots.get(i).getStack();
            total[i] = st.isEmpty() ? Double.NaN : priceOf(st);
            own[i] = !st.isEmpty() && MarketAnalyzer.isOwn(st);
            if (!Double.isNaN(total[i])) {
                priced++;
                if (own[i]) ownCount++;
            }
        }
        boolean sortedOk = isTrustworthy(total, pg[0], pg[1]);
        boolean trusted = histOn && sortedOk;

        int matchedItems = 0, matchedOffers = 0;
        String item = "";
        double cheapest = 0;
        boolean recorded = false;
        for (BkBridge.BkTarget t : ts) {
            List<Double> perPiece = new ArrayList<>();
            List<Autopilot.Offer> offers = new ArrayList<>();
            for (int i = 0; i < end; i++) {
                if (Double.isNaN(total[i])) continue;
                ItemStack st = h.slots.get(i).getStack();
                if (!t.matches(st)) continue;
                double pp = total[i] / Math.max(1, st.getCount());
                if (!own[i]) perPiece.add(pp);
                offers.add(new Autopilot.Offer(pp, st.getCount(), own[i], identity(st, total[i])));
            }
            if (offers.isEmpty()) continue;
            Collections.sort(perPiece);
            matchedItems++;
            matchedOffers += perPiece.size();
            item = t.displayName();
            cheapest = perPiece.isEmpty() ? 0 : perPiece.get(0);
            if (ap != null) ap.onObserved(t, offers, sortedOk, now);
            if (!trusted || perPiece.isEmpty()) continue;
            int k = Math.min(3, perPiece.size());
            double a3 = 0;
            for (int i = 0; i < k; i++) a3 += perPiece.get(i);
            a3 /= k;
            hist.record(t.key(), now, perPiece.get(0), a3, perPiece.size(), PriceHistory.SRC_PASSIVE_TRUSTED);
            recorded = true;
        }
        if (ap != null) ap.flush();

        status.lastReadAt = now;
        status.reads++;
        status.priced = priced;
        status.ownOffers = ownCount;
        status.matchedItems = matchedItems;
        status.matchedOffers = matchedOffers;
        status.item = matchedItems == 1 ? item : "";
        status.cheapestPP = matchedItems == 1 ? cheapest : 0;
        status.trusted = sortedOk;
        status.recorded = recorded;
        status.autopilot = ap != null && matchedItems > 0;
    }

    /** Ta sama oferta przy kolejnych odświeżeniach okna: nazwa, ilość, cena i lore (w lore jest sprzedawca). */
    static String identity(ItemStack st, double total) {
        StringBuilder sb = new StringBuilder();
        sb.append(st.getName().getString()).append('|').append(st.getCount()).append('|').append(Math.round(total * 100));
        net.minecraft.component.type.LoreComponent lore = st.get(net.minecraft.component.DataComponentTypes.LORE);
        if (lore != null) for (net.minecraft.text.Text l : lore.lines()) sb.append('|').append(l.getString());
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    /** Lista przedmiotów BK do obserwacji i autopilota (odświeżana, gdy BK zmieni listę cen). */
    static List<BkBridge.BkTarget> targets(AnalizaConfig cfg) throws Exception {
        ensureTargets(cfg);
        return targets;
    }

    /**
     * Czy z tego okna da się odczytać prawdziwe minimum cen? Tak, gdy rynek ma jedną stronę (widać wszystkie oferty)
     * albo to strona 1 i ceny rosną w kolejności slotów (posortowane od najniższej ceny).
     */
    static boolean isTrustworthy(double[] totals, int page, int pages) {
        int priced = 0;
        double prev = -1;
        boolean nonDecreasing = true;
        for (double t : totals) {
            if (Double.isNaN(t)) continue;
            priced++;
            if (prev >= 0 && t < prev - 1e-9) nonDecreasing = false;
            prev = t;
        }
        if (priced == 0) return false;
        if (pages <= 1) return true;
        return page == 1 && priced >= 5 && nonDecreasing;
    }

    private static double priceOf(ItemStack st) {
        double v = BkBridge.extractPriceObserver(st);
        if (Double.isNaN(v) || v <= 0) v = MarketAnalyzer.parsePrice(st);
        return v > 0 ? v : Double.NaN;
    }

    private static void ensureTargets(AnalizaConfig cfg) throws Exception {
        long v = BkBridge.priceVersion();
        long now = System.currentTimeMillis();
        boolean stale = v != targetsVersion
                || (v < 0 && now - targetsBuiltAt > 30_000)
                || (targets.isEmpty() && now - targetsBuiltAt > 5_000);
        if (stale) {
            targets = BkBridge.observerTargets(cfg);
            targetsVersion = v;
            targetsBuiltAt = now;
        }
    }
}
