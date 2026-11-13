package pl.rynekanaliza;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Analiza rynku dla BK-Rynek.
 *   Klawisz "otwórz panel" (domyślnie prawy Shift) - panel z listą przedmiotów, cenami, wykresami i ustawieniami
 *   Klawisz "start/stop" (domyślnie F8)            - start/stop analizy wszystkich przedmiotów
 *   Oba klawisze zmienisz w panelu (zakładka Ustawienia) albo w Sterowaniu Minecrafta.
 *
 * Analiza wpisuje do BK CENĘ SPRZEDAŻY (średnia 3 najtańszych ofert za sztukę x ilość sprzedaży z BK) i CENĘ SKUPU
 * (najtańsza oferta minus marża z progu ceny - progi w Ustawieniach). Autopilot (Autopilot) co kilkanaście minut skanuje
 * wszystkie przedmioty z BK i sam ustala sprzedaż (pod najtańszą ofertę) i skup.
 *
 * Komendy (opcjonalnie):
 *   /analiza start [fragment nazwy]   /analiza stop   /analiza lista
 */
public class RynekAnalizaMod implements ClientModInitializer {
    private static AnalizaConfig cfg;
    private static PriceHistory history;
    private static Autopilot autopilot;
    private static boolean bkWasRunning;
    private static int apTicker;
    // harmonogram skanów autopilota (pola pakietowe - ustawiają je testy)
    static long nextPlanAt;             // kolejne sprawdzenie, czy pora na skan (co 15 s)
    static long lastCycleAt;            // start ostatniego skanu wszystkich przedmiotów
    static long lastCycleEndAt;         // koniec ostatniego skanu autopilota (pełnego albo ponownego)
    private static int pendingScans;    // ile przedmiotów obejmie najbliższy skan wszystkich (zakładka Autopilot, znaczek)
    /** Podmieniane w testach: czy BK pracuje, świeże wpisy BK, start skanu autopilota (lista, czy skan wszystkich). */
    static java.util.function.BooleanSupplier bkRunning = BkBridge::isRunning;
    static java.util.concurrent.Callable<List<BkBridge.BkTarget>> freshTargets = () -> BkBridge.targets("", cfg);
    static java.util.function.BiConsumer<List<BkBridge.BkTarget>, Boolean> cycleStarter = (pick, full) -> startTargets(pick, true, full);
    static final int NONE = 0, FULL = 1, RESCAN = 2;
    /** Po skanie BK pracuje co najmniej tyle, zanim ruszy następny skan wszystkich (długa lista nie zatrzyma go na stałe). */
    static final long MIN_BREAK_MS = 2 * 60_000L;
    /** Ponowny skan po zakupach - nie wcześniej niż tyle po poprzednim skanie. */
    static final long RESCAN_GAP_MS = 30_000L;
    private static boolean warnedAutopilot;

    public static KeyBinding openKey;
    public static KeyBinding toggleKey;

    public static AnalizaConfig config() { return cfg; }

    public static PriceHistory history() { return history; }

    public static Autopilot autopilot() { return autopilot; }

    @Override
    public void onInitializeClient() {
        cfg = AnalizaConfig.load();
        cfg.applyToAnalyzer();
        history = PriceHistory.load();
        autopilot = Autopilot.load(cfg);
        MarketAnalyzer.quietSink = line -> autopilot.scanLine(line);
        MarketAnalyzer.init();
        ClientTickEvents.END_CLIENT_TICK.register(MarketObserver::tick);
        ClientTickEvents.END_CLIENT_TICK.register(RynekAnalizaMod::autopilotTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            history.saveBlocking();
            autopilot.save();
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof HandledScreen<?>) {
                ScreenEvents.afterRender(screen).register((scr, ctx, mouseX, mouseY, tickDelta) -> {
                    AnalysisOverlay.render(scr, ctx);
                    StatusBadge.renderScreen(scr, ctx); // znaczek "Rynek Analiza: działa" obok okna rynku
                });
            }
        });
        // ten sam znaczek na ekranie gry, gdy BK pracuje, a okno rynku jest zamknięte
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> StatusBadge.renderHud(ctx));

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rynek_analiza.open_gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.rynek_analiza"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rynek_analiza.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.rynek_analiza"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) toggleAnalysis();
            while (openKey.wasPressed()) openGui(client);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("analiza")
                        .then(ClientCommandManager.literal("start")
                                .executes(ctx -> { startAnalysis(""); return 1; })
                                .then(ClientCommandManager.argument("fragment", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            startAnalysis(StringArgumentType.getString(ctx, "fragment"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("stop")
                                .executes(ctx -> { MarketAnalyzer.stop(); return 1; }))
                        .then(ClientCommandManager.literal("lista")
                                .executes(ctx -> { printList(ctx.getSource()); return 1; }))));
    }

    // ---------- panel i start/stop ----------
    public static void openGui(MinecraftClient client) {
        if (client.currentScreen != null) return;
        if (MarketAnalyzer.isRunning()) {
            MarketAnalyzer.log.accept(MarketAnalyzer.isCycle()
                    ? "Trwa skan autopilota (zwykle kilkanaście sekund) - panel otworzysz po nim albo przerwij go klawiszem start/stop."
                    : "Analiza trwa - zatrzymaj ją klawiszem start/stop, żeby otworzyć panel.");
            return;
        }
        if (!BkBridge.available()) {
            MarketAnalyzer.log.accept("Nie widzę BK-Rynek 3.0 (" + BkBridge.error() + ").");
            return;
        }
        client.setScreen(new AnalizaScreen(cfg));
    }

    public static void toggleAnalysis() {
        if (MarketAnalyzer.isRunning()) MarketAnalyzer.stop();
        else startAnalysis("");
    }

    public static void startAnalysis(String filter) {
        if (MarketAnalyzer.isRunning()) {
            MarketAnalyzer.log.accept("Analiza już trwa (klawisz start/stop albo /analiza stop, żeby przerwać).");
            return;
        }
        if (!BkBridge.available()) {
            MarketAnalyzer.log.accept("Nie widzę BK-Rynek 3.0 (" + BkBridge.error() + ").");
            return;
        }
        try {
            List<BkBridge.BkTarget> targets = BkBridge.targets(filter, cfg);
            if (targets.isEmpty()) {
                MarketAnalyzer.log.accept("Brak przedmiotów na liście BK" + (filter.isBlank() ? "." : " pasujących do: " + filter));
                return;
            }
            if (!BkBridge.usesBkMatcher()) {
                MarketAnalyzer.log.accept("Uwaga: używam własnego dopasowania (nie BK): " + BkBridge.error());
            }
            startTargets(targets, false);
        } catch (Throwable t) {
            MarketAnalyzer.log.accept("Błąd startu analizy: " + t);
        }
    }

    /** Analiza podanych przedmiotów (zwykła: z panelu, klawisza albo komendy). */
    static void startTargets(List<BkBridge.BkTarget> targets, boolean cycle) {
        startTargets(targets, cycle, true);
    }

    /**
     * Analiza podanych przedmiotów; cycle = skan autopilota (cichy, bez wpisywania średniej 3, BK zawsze wznawiany),
     * full = skan wszystkich przedmiotów (false = ponowny skan po zakupach).
     */
    static void startTargets(List<BkBridge.BkTarget> targets, boolean cycle, boolean full) {
        cfg.applyToAnalyzer();
        MarketAnalyzer.PRICE_REGEX = BkBridge.priceRegex();

        bkWasRunning = BkBridge.isRunning();
        if (bkWasRunning) BkBridge.stopBk();

        MarketAnalyzer.cycle = cycle;
        if (cycle) autopilot.beginCycle(full);
        MarketAnalyzer.onFinished = () -> {
            MarketAnalyzer.cycle = false;
            cfg.save();        // ostatnie pomiary
            history.save();    // historia cen
            if (cycle) {
                autopilot.endCycle();
                lastCycleEndAt = System.currentTimeMillis();
            } else {
                autopilot.save();
            }
            BkBridge.commit(); // ceny do BK
            if (bkWasRunning && (cycle || cfg.resumeBk)) BkBridge.startBk();
        };
        if (!MarketAnalyzer.start(targets)) {
            MarketAnalyzer.cycle = false;
            if (cycle) lastCycleEndAt = System.currentTimeMillis();
            MarketAnalyzer.log.accept("Nie udało się wystartować analizy.");
            if (bkWasRunning && (cycle || cfg.resumeBk)) BkBridge.startBk();
        }
    }

    // ---------- autopilot: zakupy/sprzedaże z BK, skan wszystkich co apIntervalMin minut, ponowne skany po zakupach ----------
    private static void autopilotTick(MinecraftClient mc) {
        if (cfg == null || autopilot == null || !cfg.autopilot) return;
        if (++apTicker < 20) return; // raz na sekundę
        apTicker = 0;
        long now = System.currentTimeMillis();
        try {
            if (mc.player == null || mc.world == null || !BkBridge.available()) return;
            List<BkBridge.BkTarget> ts = MarketObserver.targets(cfg);
            for (BkBridge.Purchase p : BkBridge.pollPurchases()) autopilot.onPurchase(ts, p.stack, p.total, p.count, now);
            double[] sc = BkBridge.salesCounters();
            if (sc != null) autopilot.onSalesCounters(ts, (int) sc[0], sc[1], now);
            autopilot.tick(ts, now);
            autopilot.flush();
            maybeStartCycle(mc, ts, now);
        } catch (Throwable t) {
            if (!warnedAutopilot) {
                warnedAutopilot = true;
                MarketAnalyzer.log.accept("Autopilot: błąd (" + t + ") - sprawdź log gry.");
            }
            t.printStackTrace();
        }
    }

    /**
     * Skany autopilota:
     *  - co apIntervalMin minut (licząc od startu poprzedniego) - WSZYSTKIE przedmioty z listy BK; po skanie BK pracuje
     *    co najmniej MIN_BREAK_MS, nawet gdy skan długiej listy trwał dłużej niż odstęp,
     *  - przedmiot, który BK kupił apRescanBuys razy od jego ostatniego skanu - od razu sam (ponowny skan).
     * Tylko gdy BK pracuje (wtedy i tak stoi na rynku) i gracz nie ma otwartego innego okna (np. ekwipunku albo tego panelu).
     * BK jest zatrzymywany na czas skanu i wznawiany po nim.
     */
    static void maybeStartCycle(MinecraftClient mc, List<BkBridge.BkTarget> ts, long now) throws Exception {
        if (MarketAnalyzer.isRunning() || now < nextPlanAt) return;
        nextPlanAt = now + 15_000L;
        List<Autopilot.Pick> plan = autopilot.plan(ts, now);
        pendingScans = plan.size();
        int rescans = 0;
        for (Autopilot.Pick pk : plan) if (pk.kind == Autopilot.Pick.RESCAN) rescans++;
        int kind = cycleKind(now, plan.size(), rescans);
        if (kind == NONE) return;
        if (!bkRunning.getAsBoolean()) return;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof HandledScreen<?> hs && MarketAnalyzer.isMarket(hs))) return;
        List<BkBridge.BkTarget> fresh = freshTargets.call(); // świeże wpisy BK (te z obserwacji są tylko do odczytu)
        List<BkBridge.BkTarget> pick = new ArrayList<>();
        for (Autopilot.Pick pk : plan) {
            if (kind == RESCAN && pk.kind != Autopilot.Pick.RESCAN) continue;
            for (BkBridge.BkTarget f : fresh) if (f.key().equals(pk.t.key())) { pick.add(f); break; }
        }
        if (pick.isEmpty()) return;
        if (kind == FULL) lastCycleAt = now;
        cycleStarter.accept(pick, kind == FULL);
    }

    /**
     * Jaki skan teraz: FULL = pora na skan wszystkich (odstęp od startu poprzedniego i przerwa po końcu ostatniego),
     * RESCAN = tylko przedmioty po zakupach (nie wcześniej niż RESCAN_GAP_MS po ostatnim skanie), NONE = nic.
     */
    static int cycleKind(long now, int planned, int rescans) {
        if (planned <= 0) return NONE;
        if (now >= nextFullScanAt()) return FULL;
        if (rescans > 0 && now - lastCycleEndAt >= RESCAN_GAP_MS) return RESCAN;
        return NONE;
    }

    static long intervalMs() {
        return Math.max(2, cfg.apIntervalMin) * 60_000L;
    }

    /** Kiedy może ruszyć następny skan wszystkich: odstęp od startu poprzedniego i przerwa po końcu ostatniego skanu. */
    static long nextFullScanAt() {
        return Math.max(lastCycleAt + intervalMs(), lastCycleEndAt + MIN_BREAK_MS);
    }

    /** Za ile ms autopilot zrobi następny skan wszystkich przedmiotów (0 = zaraz). */
    public static long msToNextCycle() {
        return Math.max(0, nextFullScanAt() - System.currentTimeMillis());
    }

    /** Ile przedmiotów obejmie najbliższy skan wszystkich (odświeżane co 15 s). */
    public static int pendingScans() {
        return pendingScans;
    }

    private static void printList(FabricClientCommandSource src) {
        try {
            if (!BkBridge.available()) { src.sendFeedback(Text.literal("[Analiza] Brak BK-Rynek: " + BkBridge.error())); return; }
            for (String line : BkBridge.describe(cfg)) src.sendFeedback(Text.literal("[Analiza] " + line));
        } catch (Throwable t) {
            src.sendFeedback(Text.literal("[Analiza] Błąd: " + t));
        }
    }
}
