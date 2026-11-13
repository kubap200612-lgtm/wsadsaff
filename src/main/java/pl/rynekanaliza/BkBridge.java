package pl.rynekanaliza;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Most do BK-Rynek 3.0 (1.21.4). Wszystko przez refleksję, więc ten mod nie ma zależności kompilacji od BK.
 *
 * Z jaru BK (sprawdzone na nazwach klas/metod, nie na działającej grze):
 *  - MarketService.shared().items()  -> żywa lista wpisów aktywnego profilu (core.config.PriceEntry, publiczne pola:
 *                                       name, material, lore, customModelData, maxPrice = cena skupu (za sztukę),
 *                                       sellPrice = cena sprzedaży CAŁEJ wystawki, sellCount = ilość sztuk w wystawce, ...)
 *  - auto-sprzedaż BK wysyła "/wystaw {quantity} {price}" z quantity = sellCount (brak = 1) i price = (int) sellPrice,
 *    więc sellPrice to cena za sellCount sztuk (np. złote jabłka: 64 szt. za 3289$)
 *  - MarketService.save()            -> zapis pamięci do configu na dysk
 *  - ClientPriceListManager.markDirty() (prywatna) podbija wersję list cen, żeby auto-kupno i podświetlanie
 *                                       przeładowały ceny
 *  - AutoMarketScanner.matchesEntry(ItemStack, clean.PriceEntry) -> to samo dopasowanie, którego używa auto-kupno
 *  - CleanMarketEngine.debug(ItemStack) -> raport BK "dlaczego ten przedmiot (nie) pasuje" (do diagnostyki)
 */
public final class BkBridge {
    private static final String P = "pl.bkubiak.bkrynek.";

    private static boolean inited;
    private static boolean ok;
    private static boolean matcherOk;
    private static String error = "";

    private static Method mShared, mItems, mProfile, mRunning, mStart, mStop, mSave;
    private static Method mMarkDirty, mFullReload, mAllEntries, mMatches, mExtractPrice, mGetVersion, mDebug;
    private static Field fOriginals, fServersConfig, fIgnoreComponents;
    private static Object scanner;          // skaner silnika BK (do własnej analizy, gdy BK jest zatrzymany)
    private static Object observerScanner;  // osobna instancja do obserwacji w trakcie pracy BK (nie dzielimy stanu z jego wątkami)
    private static boolean priceOk;

    private BkBridge() {}

    // ---------- inicjalizacja ----------
    private static synchronized void init() {
        if (inited) return;
        inited = true;
        try {
            Class<?> cService = Class.forName(P + "client.market.MarketService");
            mShared = cService.getMethod("shared");
            mItems = cService.getMethod("items");
            mProfile = cService.getMethod("profile");
            mRunning = cService.getMethod("running");
            mStart = cService.getMethod("start");
            mStop = cService.getMethod("stop");
            mSave = cService.getMethod("save");

            Class<?> cClient = Class.forName(P + "client.BkRynekClient");
            fServersConfig = cClient.getField("serversConfig");

            Class<?> cPrices = Class.forName(P + "core.manager.ClientPriceListManager");
            mFullReload = cPrices.getMethod("fullReloadMemory", fServersConfig.getType());
            try { mGetVersion = cPrices.getMethod("getVersion"); } catch (NoSuchMethodException ignored) {}
            try {
                mMarkDirty = cPrices.getDeclaredMethod("markDirty");
                mMarkDirty.setAccessible(true);
            } catch (NoSuchMethodException ignored) {}
            try {
                fIgnoreComponents = cPrices.getDeclaredField("ignoreComponents");
                fIgnoreComponents.setAccessible(true);
            } catch (NoSuchFieldException ignored) {}
            ok = true;
        } catch (Throwable t) {
            ok = false;
            error = t.toString();
            return;
        }
        try {
            Class<?> cEngine = Class.forName(P + "client.market.CleanMarketEngine");
            mAllEntries = cEngine.getMethod("allEntries");
            try { mDebug = cEngine.getMethod("debug", ItemStack.class); } catch (NoSuchMethodException ignored) {}
            fOriginals = cEngine.getDeclaredField("originals");
            fOriginals.setAccessible(true);
            Class<?> cScanner = Class.forName(P + "client.market.clean.AutoMarketScanner");
            // najlepiej użyć dokładnie tego skanera, którego używa silnik BK (ma ustawione regexy profilu)
            for (Field f : cEngine.getDeclaredFields()) {
                if (f.getType() == cScanner && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    scanner = f.get(null);
                    break;
                }
            }
            boolean ownScanner = false;
            if (scanner == null) {
                scanner = cScanner.getConstructor().newInstance();
                ownScanner = true;
            }
            for (Method m : cScanner.getMethods()) {
                if (m.getName().equals("matchesEntry") && m.getParameterCount() == 2) mMatches = m;
                if (m.getName().equals("extractPrice") && m.getParameterCount() == 1) mExtractPrice = m;
            }
            if (ownScanner) configureScanner(cScanner, scanner);
            try {
                observerScanner = cScanner.getConstructor().newInstance();
                configureScanner(cScanner, observerScanner);
            } catch (Throwable t) {
                observerScanner = null;
            }
            matcherOk = mMatches != null;
            priceOk = mExtractPrice != null;
        } catch (Throwable t) {
            matcherOk = false;
            error = "dopasowanie BK niedostępne (używam własnego): " + t;
        }
    }

    public static boolean available() {
        if (!FabricLoader.getInstance().isModLoaded("bkrynek")) return false;
        init();
        return ok;
    }

    public static String error() {
        init();
        return error;
    }

    public static boolean usesBkMatcher() {
        init();
        return matcherOk;
    }

    /** Gdy nie znaleziono skanera silnika BK: nasz własny dostaje regexy z aktywnego profilu. */
    private static void configureScanner(Class<?> cScanner, Object target) {
        try {
            Object profile = mProfile.invoke(service());
            String lore = (String) get(profile, "loreRegex");
            String seller = (String) get(profile, "loreSellerRegex");
            if (lore != null && !lore.isBlank()) cScanner.getMethod("setLoreRegex", String.class).invoke(target, lore);
            if (seller != null && !seller.isBlank()) cScanner.getMethod("setLoreSellerRegex", String.class).invoke(target, seller);
        } catch (Throwable ignored) {}
    }

    /** Cena całej oferty wg AutoMarketScanner.extractPrice z BK (pomija m.in. linie "koszt użycia"); NaN gdy brak. */
    static double extractPrice(ItemStack st) {
        return extractPrice(st, scanner);
    }

    /** To samo, ale na osobnym skanerze - do obserwacji, gdy BK pracuje. */
    static double extractPriceObserver(ItemStack st) {
        return extractPrice(st, observerScanner != null ? observerScanner : scanner);
    }

    private static double extractPrice(ItemStack st, Object sc) {
        if (!priceOk || sc == null) return Double.NaN;
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return Double.NaN;
        List<String> lines = new ArrayList<>();
        for (Text t : lore.lines()) lines.add(t.getString());
        try {
            Object r = mExtractPrice.invoke(sc, lines);
            double v = r instanceof Number n ? n.doubleValue() : Double.NaN;
            return v > 0 ? v : Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    // ---------- sterowanie BK ----------
    private static Object service() throws Exception {
        return mShared.invoke(null);
    }

    public static boolean isRunning() {
        try { return (Boolean) mRunning.invoke(service()); } catch (Throwable t) { return false; }
    }

    public static void stopBk() {
        try { mStop.invoke(service()); } catch (Throwable t) { MarketAnalyzer.log.accept("Nie udało się zatrzymać BK: " + t); }
    }

    public static void startBk() {
        try { mStart.invoke(service()); } catch (Throwable t) { MarketAnalyzer.log.accept("Nie udało się wznowić BK: " + t); }
    }

    /** Zapis cen na dysk + powiadomienie BK, że ceny się zmieniły. */
    public static void commit() {
        try {
            mSave.invoke(service());
            if (mMarkDirty != null) mMarkDirty.invoke(null);
            else mFullReload.invoke(null, fServersConfig.get(null));
        } catch (Throwable t) {
            MarketAnalyzer.log.accept("Zapis cen do BK nie powiódł się: " + t);
        }
    }

    /** Wersja listy cen BK (zmienia się po każdej edycji listy); -1, gdy nieznana. */
    public static long priceVersion() {
        init();
        try { return mGetVersion == null ? -1 : ((Number) mGetVersion.invoke(null)).longValue(); } catch (Throwable t) { return -1; }
    }

    /** lore_regex z aktywnego profilu BK (grupa 1 = liczba) albo null. */
    public static Pattern priceRegex() {
        try {
            Object profile = mProfile.invoke(service());
            String rx = (String) get(profile, "loreRegex");
            if (rx == null || rx.isBlank()) return null;
            return Pattern.compile(rx);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- cele analizy ----------
    public static List<BkTarget> targets(String filter, AnalizaConfig cfg) throws Exception {
        init();
        List<?> items = new ArrayList<>((List<?>) mItems.invoke(service()));
        IdentityHashMap<Object, Object> coreToClean = new IdentityHashMap<>();
        if (matcherOk) {
            try {
                mAllEntries.invoke(null); // wymusza odświeżenie wewnętrznej listy BK
                Map<?, ?> originals = (Map<?, ?>) fOriginals.get(null);
                for (Map.Entry<?, ?> e : originals.entrySet()) coreToClean.put(e.getValue(), e.getKey());
            } catch (Throwable t) {
                error = "nie odczytałem wpisów BK (używam własnego dopasowania): " + t;
            }
        }
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<BkTarget> all = new ArrayList<>();
        for (Object core : items) all.add(new BkTarget(core, coreToClean.get(core), cfg));
        assignKeys(all); // klucze liczone na całej liście, żeby filtr nie zmieniał kluczy
        List<BkTarget> out = new ArrayList<>();
        for (BkTarget t : all) {
            if (f.isEmpty() || t.displayName().toLowerCase(Locale.ROOT).contains(f)) out.add(t);
        }
        return out;
    }

    /**
     * Rozróżnia wpisy o tej samej nazwie i id (np. 5 x "Zaklęta książka", 2 x "Diamentowy miecz" różniące się lore/CMD).
     * Wpis z unikalną nazwą zostaje przy starym kluczu "nazwa|material" (jego historia i wykres się nie zmieniają);
     * wpisy z powtórzoną nazwą dostają klucz z dopiskiem (lore, CMD, enchanty) i etykietę do pokazania przy nazwie.
     */
    static void assignKeys(List<BkTarget> all) {
        Map<String, List<BkTarget>> groups = new java.util.LinkedHashMap<>();
        for (BkTarget t : all) groups.computeIfAbsent(t.baseKey(), k -> new ArrayList<>()).add(t);
        for (List<BkTarget> g : groups.values()) {
            if (g.size() < 2) continue;
            java.util.Set<String> usedKeys = new java.util.HashSet<>(), usedLabels = new java.util.HashSet<>();
            int n = 0;
            for (BkTarget t : g) {
                n++;
                String lore = t.firstLoreLine();
                Object cmd = get(t.core, "customModelData");
                String ench = str(t.core, "enchants").trim();
                String key = (t.baseKey() + "|" + lore + "|cmd" + (cmd == null ? "" : cmd) + "|" + ench).toLowerCase(Locale.ROOT);
                if (!usedKeys.add(key)) { key = key + "#" + n; usedKeys.add(key); }
                String label = !lore.isEmpty() ? lore : !ench.isEmpty() ? ench : cmd != null ? "CMD " + cmd : "#" + n;
                if (!usedLabels.add(label)) { label = label + " #" + n; usedLabels.add(label); }
                t.key = key;
                t.label = label;
            }
        }
    }

    /** Raport BK (CleanMarketEngine.debug) dla przedmiotu: dlaczego pasuje / nie pasuje do wpisów. Pusta lista, gdy niedostępny. */
    static List<String> bkDebug(ItemStack st) {
        init();
        if (mDebug == null || st == null || st.isEmpty()) return List.of();
        try {
            Object r = mDebug.invoke(null, st);
            List<String> out = new ArrayList<>();
            if (r instanceof List<?> l) for (Object o : l) out.add(strip(String.valueOf(o)));
            return out;
        } catch (Throwable t) {
            return List.of("raport BK niedostępny: " + t);
        }
    }

    /** Dopisuje raport diagnostyczny do config/rynek-analiza-diagnostyka.txt (ostatnie raporty na końcu). */
    static java.nio.file.Path writeDiagnostics(String title, List<String> lines) {
        try {
            java.nio.file.Path f = FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza-diagnostyka.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("==== ").append(java.time.LocalDateTime.now().withNano(0)).append(" | ").append(title).append(" ====\n");
            for (String l : lines) sb.append(l).append('\n');
            sb.append('\n');
            java.nio.file.Files.writeString(f, sb.toString(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Cele do obserwacji rynku w trakcie pracy BK: dopasowanie i ceny liczone na osobnym skanerze. */
    public static List<BkTarget> observerTargets(AnalizaConfig cfg) throws Exception {
        List<BkTarget> l = targets("", cfg);
        for (BkTarget t : l) t.passive = true;
        return l;
    }

    public static List<String> describe(AnalizaConfig cfg) throws Exception {
        List<String> lines = new ArrayList<>();
        for (BkTarget t : targets("", cfg)) {
            lines.add(String.format("%s | skup %s$ | sprzedaż %s$ za %d szt.", t.displayName(),
                    AnalizaScreen.fmt(t.currentBuyPrice()), AnalizaScreen.fmt(t.currentSellPrice()), t.sellCount()));
        }
        return lines;
    }

    // ---------- historia zakupów i liczniki sprzedaży BK (AutoMarketHistoryManager) ----------
    /** Zakup zrobiony przez BK: kupiony stack (kopia), cena całej oferty, liczba sztuk. */
    public static final class Purchase {
        public final ItemStack stack;
        public final double total;
        public final int count;

        Purchase(ItemStack stack, double total, int count) {
            this.stack = stack;
            this.total = total;
            this.count = count;
        }
    }

    private static boolean histInited;
    private static Method mHistInstance, mHistList, mHistSold, mHistRevenue, rStack, rPrice, rCount;
    private static final java.util.Set<Object> seenPurchases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean purchasesPrimed;

    private static synchronized void initHistory() {
        if (histInited) return;
        histInited = true;
        try {
            Class<?> c = Class.forName(P + "client.market.clean.AutoMarketHistoryManager");
            Class<?> e = Class.forName(P + "client.market.clean.AutoMarketHistoryManager$HistoryEntry");
            Method inst = c.getMethod("getInstance");
            mHistList = c.getMethod("getHistory");
            mHistSold = c.getMethod("getSoldCount");
            mHistRevenue = c.getMethod("getTotalRevenue");
            rStack = e.getMethod("stack");
            rPrice = e.getMethod("price");
            rCount = e.getMethod("count");
            mHistInstance = inst;
        } catch (Throwable t) {
            mHistInstance = null;
        }
    }

    /**
     * Nowe zakupy z historii BK (BK trzyma 5 ostatnich, najnowszy na początku). Pierwsze wywołanie tylko zapamiętuje
     * to, co już tam jest (zakupy sprzed uruchomienia nie są liczone).
     */
    public static List<Purchase> pollPurchases() {
        initHistory();
        if (mHistInstance == null) return List.of();
        try {
            Object mgr = mHistInstance.invoke(null);
            if (mgr == null) return List.of();
            List<?> l = new ArrayList<>((List<?>) mHistList.invoke(mgr));
            List<Purchase> out = new ArrayList<>();
            if (purchasesPrimed) {
                for (int i = l.size() - 1; i >= 0; i--) {
                    Object e = l.get(i);
                    if (e == null || seenPurchases.contains(e)) continue;
                    Object st = rStack.invoke(e);
                    double price = ((Number) rPrice.invoke(e)).doubleValue();
                    int count = ((Number) rCount.invoke(e)).intValue();
                    if (st instanceof ItemStack is) out.add(new Purchase(is, price, Math.max(1, count)));
                }
            }
            seenPurchases.clear();
            seenPurchases.addAll(l);
            purchasesPrimed = true;
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** {liczba sprzedanych, suma przychodu} z liczników BK; null, gdy niedostępne. */
    public static double[] salesCounters() {
        initHistory();
        if (mHistInstance == null) return null;
        try {
            Object mgr = mHistInstance.invoke(null);
            if (mgr == null) return null;
            return new double[]{((Number) mHistSold.invoke(mgr)).doubleValue(), ((Number) mHistRevenue.invoke(mgr)).doubleValue()};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Globalny tryb "Unit" profilu BK (cena skupu za sztukę); null = nie wiadomo. */
    /** Tylko testy: udawany tryb "Unit" profilu BK (null = odczyt z BK). */
    static Boolean unitModeOverride;

    static Boolean globalUnitMode() {
        if (unitModeOverride != null) return unitModeOverride;
        try {
            init();
            if (!ok) return null;
            Object v = get(mProfile.invoke(service()), "unitMode");
            return v instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- odczyt pól wpisu BK ----------
    static Object get(Object o, String field) {
        if (o == null) return null;
        try { return o.getClass().getField(field).get(o); } catch (Throwable t) { return null; }
    }

    static String str(Object o, String field) {
        Object v = get(o, field);
        return v instanceof String s ? s : "";
    }

    static boolean ignoreComponents() {
        try { return fIgnoreComponents != null && fIgnoreComponents.getBoolean(null); } catch (Throwable t) { return false; }
    }

    private static String strip(String s) {
        String r = Formatting.strip(s == null ? "" : s);
        return r == null ? "" : r.trim();
    }

    /** Przedmiot z listy BK jako cel analizy. */
    public static final class BkTarget implements MarketAnalyzer.Target {
        private final Object core;
        private final Object clean;
        private final AnalizaConfig cfg;
        private boolean warnedMatcher;
        boolean passive;
        /** Klucz w configu i historii; zwykle "nazwa|material", przy powtórzonych nazwach z dopiskiem (patrz assignKeys). */
        String key;
        /** Dopisek do nazwy, gdy kilka wpisów ma tę samą nazwę (np. pierwsza linia lore). */
        String label = "";

        BkTarget(Object core, Object clean, AnalizaConfig cfg) {
            this.core = core;
            this.clean = clean;
            this.cfg = cfg;
            this.key = baseKey();
        }

        /** Nazwa bez kolorów (albo id, gdy wpis nie ma nazwy). */
        public String baseName() {
            String n = strip(str(core, "name"));
            return n.isEmpty() ? str(core, "material") : n;
        }

        @Override
        public String displayName() {
            return label.isEmpty() ? baseName() : baseName() + " [" + label + "]";
        }

        String baseKey() {
            return (strip(str(core, "name")) + "|" + str(core, "material")).toLowerCase(Locale.ROOT);
        }

        /** Pierwsza niepusta linia wymaganego lore wpisu (bez kolorów). */
        String firstLoreLine() {
            for (String part : str(core, "lore").split("[;\\n]+")) {
                String p = strip(part);
                if (!p.isEmpty()) return p;
            }
            return "";
        }

        @Override
        public ItemStack icon() {
            String id = str(core, "material").trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) return ItemStack.EMPTY;
            Identifier ident = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (ident == null) return ItemStack.EMPTY;
            return new ItemStack(Registries.ITEM.get(ident));
        }

        public String key() {
            return key;
        }

        /** Cena skupu w BK (maxPrice, za sztukę) - ustawiana ręcznie przez gracza. */
        public double currentBuyPrice() {
            Object v = get(core, "maxPrice");
            return v instanceof Number n ? n.doubleValue() : 0;
        }

        /** Cena sprzedaży w BK (sellPrice) - za całą wystawkę sellCount() sztuk. */
        public double currentSellPrice() {
            Object v = get(core, "sellPrice");
            return v instanceof Number n ? n.doubleValue() : 0;
        }

        /** Czy w BK jest włączone "Sprzedawanie" tego przedmiotu. */
        public boolean sellEnabled() {
            return get(core, "sellEnabled") instanceof Boolean b && b;
        }

        /** Czy w BK jest włączone kupowanie tego przedmiotu (brak pola = włączone). */
        public boolean buyEnabled() {
            Object v = get(core, "buyEnabled");
            return !(v instanceof Boolean b) || b;
        }

        /** Włącza / wyłącza kupowanie w BK (autopilot: budżet i limit zakupów). Zatwierdza dopiero commit(). */
        public void setBuyEnabled(boolean on) {
            try {
                core.getClass().getField("buyEnabled").set(core, Boolean.valueOf(on));
            } catch (Throwable t) {
                MarketAnalyzer.log.accept("Nie mogę zmienić kupowania w BK: " + t);
            }
        }

        /**
         * Mnożnik ceny skupu: w trybie "Unit" (wpis albo cały profil) BK porównuje cenę za sztukę - 1;
         * bez niego porównuje cenę całej oferty o dokładnie "Ilość kupna" sztukach.
         */
        public int buyFactor() {
            if (Boolean.TRUE.equals(get(core, "unitMode"))) return 1;
            Boolean g = globalUnitMode();
            if (g == null || g) return 1;
            Object rc = get(core, "requiredCount");
            return rc instanceof Number n ? Math.max(1, n.intValue()) : 1;
        }

        /** Wynik pełnego skanu dla autopilota; tekst do logu, gdy autopilot przejmuje wpisywanie cen tego przedmiotu. */
        @Override
        public String handleScan(List<Double> othersPP, int ownOffers) {
            Autopilot ap = RynekAnalizaMod.autopilot();
            if (ap == null) return null;
            String r = ap.onScan(this, othersPP, ownOffers, System.currentTimeMillis());
            if (r == null) return null;
            return ap.live() || MarketAnalyzer.isCycle() ? r : null;
        }

        /**
         * Zwykła analiza: skup (za sztukę) jak u autopilota - najtańsza prawdziwa oferta z tej analizy (z wykresem, gdy rynek
         * jest drożej niż zwykle) minus marża z progu ceny; zawsze niżej niż sprzedaż po podatku. Nie wyżej niż "Max skup"
         * z paska przedmiotu; przedmiot z Auto: NIE zostaje przy swoim skupie. Bez trybu "Unit" BK porównuje cenę całej
         * oferty "Ilość kupna" sztuk - wtedy wpisujemy cenę za tyle sztuk.
         */
        @Override
        public String applyBuyFromAnalysis(List<Double> pricesPP, double avgPerPiece) {
            if (!cfg.analysisBuy || pricesPP == null || pricesPP.isEmpty()) return null;
            Autopilot ap = RynekAnalizaMod.autopilot();
            Autopilot.ItemState st = ap == null ? null : ap.peek(key());
            if (st != null && !st.auto) return "skup bez zmian (Auto: NIE)";
            AutoPricing.Params p = ap == null ? new AutoPricing.Params() : ap.params();
            double chart = ap == null ? 0 : ap.usual(key(), System.currentTimeMillis());
            AutoPricing.Filtered f = AutoPricing.filterOutliers(pricesPP, chart, p.outlierRatio);
            double market = f.kept.isEmpty() ? 0 : f.kept.get(0);
            int factor = Math.max(1, buyFactor());
            AutoPricing.BuyDecision d = AutoPricing.decideBuy(market, chart, cfg.buyTierUpTo, cfg.buyTierMarginPct, avgPerPiece,
                    st == null ? 0 : st.manualMaxBuy, currentBuyPrice() / factor, p);
            if (!d.changed) return d.reason;
            if (!applyBuyPrice(d.pp * factor)) return null;
            return "skup " + AnalizaScreen.fmtR(d.pp) + "$/szt." + (factor > 1 ? " (" + AnalizaScreen.fmtR(d.pp * factor) + "$ za " + factor + " szt.)" : "")
                    + " (" + d.desc + ")";
        }

        @Override
        public void onFailed(String reason) {
            Autopilot ap = RynekAnalizaMod.autopilot();
            if (ap != null) ap.onScanFailed(this, reason, System.currentTimeMillis());
        }

        /** Ile sztuk BK wystawia naraz ("Ilość sprzedaży"; brak = 1). */
        @Override
        public int sellCount() {
            Object v = get(core, "sellCount");
            int n = v instanceof Number x ? x.intValue() : 0;
            if (n <= 0) {
                Object m = get(core, "minSellCount");
                n = m instanceof Number x ? x.intValue() : 0;
            }
            return Math.max(1, n);
        }

        @Override
        public String searchQuery() {
            String custom = cfg.search.get(key());
            if (custom != null && !custom.isBlank()) return custom;
            String n = strip(str(core, "name"));
            if (!n.isEmpty()) return n;
            return str(core, "material").replace("minecraft:", "").replace('_', ' ');
        }

        /**
         * Zapasowe frazy wyszukiwania, gdy po pierwszej nie było na rynku ŻADNEGO przedmiotu o tym id
         * (np. "Anarchiczny hełm II" -> "Anarchiczny hełm" -> "Anarchiczny"). Dopasowanie BK i tak wybiera właściwy przedmiot.
         */
        @Override
        public List<String> fallbackQueries() {
            String custom = cfg.search.get(key());
            if (custom != null && !custom.isBlank()) return List.of();
            String q = searchQuery().trim();
            String[] w = q.split("\\s+");
            List<String> out = new ArrayList<>();
            if (w.length >= 2 && w[w.length - 1].matches("(?i)[ivxl]+|\\d+|\\[?[ivxl]+]?")) {
                out.add(String.join(" ", java.util.Arrays.copyOf(w, w.length - 1)));
            }
            if (w.length >= 2 && !out.contains(w[0]) && w[0].length() >= 3) out.add(w[0]);
            out.remove(q);
            return out;
        }

        /** Id przedmiotu z wpisu BK: małe litery, bez "minecraft:" (tak BK szuka wpisów w evaluateSlot). */
        private String wantedMaterial() {
            String m = str(core, "material").trim().toLowerCase(Locale.ROOT);
            return m.startsWith("minecraft:") ? m.substring("minecraft:".length()) : m;
        }

        // liczniki do diagnostyki (dlaczego nic nie pasuje)
        private int seenSameId, rejectedByBk, rejectedByComponents;
        private String exampleRejected = "";
        private ItemStack firstRejected;
        private final java.util.TreeSet<Integer> componentCountsSeen = new java.util.TreeSet<>();

        @Override
        public void resetDiagnostics() {
            seenSameId = rejectedByBk = rejectedByComponents = 0;
            exampleRejected = "";
            firstRejected = null;
            componentCountsSeen.clear();
        }

        @Override
        public int candidatesSeen() {
            return seenSameId;
        }

        @Override
        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            // BK najpierw wybiera wpisy po id przedmiotu, a dopiero potem porównuje nazwę/enchanty/lore.
            // Samo matchesEntry() id NIE sprawdza (zwykłe złote jabłko o nazwie "Zaklęte złote jabłko" by przeszło).
            String wantId = wantedMaterial();
            if (!wantId.isEmpty()) {
                String haveId = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
                if (!wantId.equals(haveId)) return false;
            }
            seenSameId++;
            if (matcherOk && clean != null) {
                try {
                    Object sc = passive && observerScanner != null ? observerScanner : scanner;
                    if (!(Boolean) mMatches.invoke(sc, stack, clean)) {
                        rejectedByBk++;
                        if (exampleRejected.isEmpty()) exampleRejected = strip(stack.getName().getString());
                        if (firstRejected == null && !passive) firstRejected = stack.copy();
                        return false;
                    }
                } catch (Throwable t) {
                    if (!warnedMatcher) {
                        warnedMatcher = true;
                        MarketAnalyzer.log.accept("Dopasowanie BK rzuciło błąd: " + t);
                    }
                    return false;
                }
            } else if (!fallbackMatches(stack)) {
                rejectedByBk++;
                if (exampleRejected.isEmpty()) exampleRejected = strip(stack.getName().getString());
                if (firstRejected == null && !passive) firstRejected = stack.copy();
                return false;
            }
            // BK (ścieżka "clean") nie sprawdza liczby komponentów, więc domyślnie my też nie.
            // Włączane w ustawieniach tylko, gdy ktoś chce odróżniać przedmioty o tej samej nazwie.
            if (cfg.strictComponents && !ignoreComponents()) {
                Object cc = get(core, "componentCount");
                int want = cc instanceof Number n ? n.intValue() : 0;
                int have = stack.getComponents().getTypes().size();
                componentCountsSeen.add(have);
                if (want > 0 && have != want) {
                    rejectedByComponents++;
                    return false;
                }
            }
            return true;
        }

        @Override
        public void onMeasured(double cheapest, double avg3, int offers) {
            long now = System.currentTimeMillis();
            cfg.lastScan.put(key(), new AnalizaConfig.ScanRecord(avg3, cheapest, offers, now));
            PriceHistory h = RynekAnalizaMod.history();
            if (h != null) h.record(key(), now, cheapest, avg3, offers, PriceHistory.SRC_ACTIVE);
        }

        /** Ostatnia analiza tego przedmiotu: z configu, a gdy jej tam nie ma (starsza wersja moda) - z historii cen. */
        public AnalizaConfig.ScanRecord lastScan() {
            AnalizaConfig.ScanRecord r = cfg.lastScan.get(key());
            if (r != null && r.avg3 > 0) return r;
            PriceHistory h = RynekAnalizaMod.history();
            PriceHistory.Point p = h == null ? null : h.lastActive(key(), Math.max(1, MarketAnalyzer.MIN_LISTINGS));
            return p == null ? null : new AnalizaConfig.ScanRecord(p.a3, p.p1, p.n, p.t);
        }

        @Override
        public double extractPrice(ItemStack stack) {
            return BkBridge.extractPrice(stack);
        }

        @Override
        public String diagnose() {
            if (seenSameId == 0) {
                return String.format("na rynku nie było żadnego przedmiotu o id '%s' (wyszukiwanie nic takiego nie pokazało albo nikt tego nie wystawia)", wantedMaterial());
            }
            return String.format("w oknie było %d przedm. o id '%s'; odrzucone przez dopasowanie BK: %d%s; odrzucone przez liczbę komponentów: %d%s; oczekiwana nazwa wpisu: '%s'",
                    seenSameId, wantedMaterial(), rejectedByBk,
                    exampleRejected.isEmpty() ? "" : " (np. \"" + exampleRejected + "\")",
                    rejectedByComponents, componentCountsSeen.isEmpty() ? "" : " (widziane liczby komponentów: " + componentCountsSeen + ")",
                    strip(str(core, "name")));
        }

        /**
         * Gdy BK odrzucił oferty tego id: raport BK dla pierwszej odrzuconej oferty zapisany do pliku, a do czatu kilka linii
         * z sekcji FAILURES (co dokładnie nie pasuje: nazwa, lore, enchanty, CMD...). Pusta lista, gdy nie ma czego raportować.
         */
        @Override
        public List<String> bkReport() {
            if (firstRejected == null) return List.of();
            List<String> rep = bkDebug(firstRejected);
            if (rep.isEmpty()) return List.of();
            java.nio.file.Path f = writeDiagnostics(displayName(), rep);
            List<String> chat = new ArrayList<>();
            boolean inFailures = false;
            for (String l : rep) {
                String t = l.trim();
                if (t.isEmpty() || t.startsWith("---")) continue;
                if (t.equalsIgnoreCase("FAILURES")) { inFailures = true; continue; }
                if (t.equalsIgnoreCase("NOTES") || t.equalsIgnoreCase("CANDIDATES")) inFailures = false;
                if (inFailures && chat.size() < 5) chat.add(t);
            }
            if (f != null) chat.add("pełny raport BK: config/" + f.getFileName());
            return chat;
        }

        /** Proste dopasowanie awaryjne: id przedmiotu + nazwa bez kolorów. */
        private boolean fallbackMatches(ItemStack stack) {
            String material = str(core, "material").replace("minecraft:", "").toLowerCase(Locale.ROOT);
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (!material.isEmpty() && !material.equals(id)) return false;
            String name = strip(str(core, "name"));
            return name.isEmpty() || name.equalsIgnoreCase(strip(stack.getName().getString()));
        }

        /** Wpisuje cenę sprzedaży CAŁEJ wystawki (sellCount() sztuk) do wpisu BK. Zatwierdza ją dopiero commit(). */
        @Override
        public void applySellPrice(double total) {
            try {
                core.getClass().getField("sellPrice").setDouble(core, total);
            } catch (Throwable t) {
                MarketAnalyzer.log.accept("Nie mogę wpisać ceny sprzedaży do BK: " + t);
            }
        }

        /** Wpisuje cenę skupu (jak "Limit kupna" w BK) - pole "Skup" w panelu albo autopilot na żywo. */
        public boolean applyBuyPrice(double pricePerPiece) {
            try {
                core.getClass().getField("maxPrice").setDouble(core, pricePerPiece);
                return currentBuyPrice() == pricePerPiece;
            } catch (Throwable t) {
                MarketAnalyzer.log.accept("Nie mogę wpisać ceny skupu do BK: " + t);
                return false;
            }
        }

        /** Cena sprzedaży z ostatniej analizy przy obecnej ilości sprzedaży; NaN, gdy brak analizy. */
        public double sellFromLastScan() {
            AnalizaConfig.ScanRecord r = lastScan();
            return r == null ? Double.NaN : MarketAnalyzer.sellFromAverage(r.avg3, sellCount());
        }
    }
}
