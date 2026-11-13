package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Znaczek "Rynek Analiza: działa" - widać, że mod naprawdę czyta rynek, gdy skanuje go BK.
 *  - przy oknie rynku (lewy margines obok okna): kropka i stan; zielony = okno odczytane, a pod spodem co mod z niego
 *    wyczytał (przedmiot, liczba ofert, najtańsza za sztukę, zapis do wykresu, autopilot). Kropka mignie przy każdym odczycie,
 *  - na ekranie gry (lewy górny róg), gdy BK pracuje, a okno rynku jest zamknięte: jedna linia (ostatni odczyt, autopilot).
 * Tylko rysuje - niczego nie klika i nie zmienia.
 */
public final class StatusBadge {
    static final int GREEN = 0xFF55FF55;
    static final int YELLOW = 0xFFFFFF55;
    static final int RED = 0xFFFF5555;
    static final int GRAY = 0xFFAAAAAA;
    static final int LIGHT = 0xFFE0E0E0;
    static final int BG = 0xC0101216;

    /** Odczyt sprzed najwyżej tylu ms liczy się jako "na bieżąco" (BK przełącza strony szybciej, niż je czytamy). */
    static final long FRESH_MS = 10_000;
    /** Tyle ms kropka świeci jaśniej po nowym odczycie. */
    static final long FLASH_MS = 700;
    /** Na ekranie gry: ostatni odczyt starszy niż to = żółty. */
    static final long HUD_STALE_MS = 120_000;
    /** Szerokość standardowego okna kontenera (rynek). */
    static final int CONTAINER_W = 176;
    /** Najwyżej tyle linii szczegółów pod stanem. */
    static final int MAX_LINES = 6;

    /**
     * Co pokazać: kolor kropki i stanu, stan, dalsze informacje (pakowane w linie), mignięcie kropki.
     * Informacja może mieć krótszą wersję po tabulatorze (np. "zapisano do wykresu\tzapisano") - na wąskim ekranie.
     */
    static final class View {
        final int color;
        final String state;
        final List<String> parts;
        final boolean flash;

        View(int color, String state, List<String> parts, boolean flash) {
            this.color = color;
            this.state = state;
            this.parts = parts;
            this.flash = flash;
        }
    }

    private StatusBadge() {}

    // ---------- co pokazać ----------

    /** Stan przy oknie rynku. */
    static View market(long now, AnalizaConfig cfg, Autopilot ap, boolean bkOk, String bkError, boolean analysis, boolean cycle,
                       MarketObserver.Status st, boolean windowRead) {
        List<String> parts = new ArrayList<>();
        if (analysis) return new View(GREEN, cycle ? "skan autopilota" : "analiza trwa", parts, false);
        if (!bkOk) {
            parts.add(bkError == null || bkError.isBlank() ? "BK-Rynek nie odpowiada" : bkError);
            return new View(RED, "nie widzę BK", parts, false);
        }
        if (!MarketObserver.active(cfg, ap)) {
            parts.add("włącz w Ustawieniach:");
            if (!cfg.historyEnabled) parts.add("'Zapisuj historię cen'");
            if (!cfg.passiveObserve) parts.add("'Obserwuj rynek, gdy działa BK'");
            return new View(GRAY, "nie obserwuje", parts, false);
        }
        if (st.errorAt > st.lastReadAt && now - st.errorAt < 60_000) {
            parts.add(st.error);
            return new View(RED, "błąd odczytu", parts, false);
        }
        boolean fresh = st.reads > 0 && now - st.lastReadAt <= FRESH_MS;
        if (!windowRead && !fresh) {
            parts.add(st.reads == 0 ? "pierwszy odczyt za chwilę" : "odczytów: " + st.reads + ", ostatni " + ago(now - st.lastReadAt));
            return new View(YELLOW, "czytam okno...", parts, false);
        }
        // najpierw liczba ofert (przy skracaniu nazwy zostaje widoczna), potem cena, zapis, autopilot, licznik odczytów
        if (st.matchedItems == 0) {
            parts.add(st.priced == 0 ? "okno bez ofert" : "brak przedmiotów z listy BK\tnic z listy BK");
            if (st.priced > 0) parts.add("ofert w oknie: " + st.priced + "\tofert: " + st.priced);
        } else if (st.matchedItems == 1) {
            if (st.matchedOffers == 0) parts.add("tylko Twoje oferty: " + st.item);
            else {
                parts.add(st.matchedOffers + " " + ofert(st.matchedOffers) + ": " + st.item);
                if (st.cheapestPP > 0) parts.add("od " + AnalizaScreen.fmtR(st.cheapestPP) + "$/szt.\t" + AnalizaScreen.fmtR(st.cheapestPP) + "$/szt.");
            }
        } else {
            parts.add(st.matchedOffers + " " + ofert(st.matchedOffers) + " (" + st.matchedItems + " przedm. z listy)\t"
                    + st.matchedOffers + " " + ofert(st.matchedOffers));
        }
        boolean histOn = cfg.historyEnabled && cfg.passiveObserve;
        if (st.recorded) parts.add("zapisano do wykresu\tzapisano");
        else if (histOn && st.matchedOffers > 0 && !st.trusted) parts.add("bez zapisu: nie od najtańszych\tbez zapisu");
        if (st.autopilot) parts.add("autopilot sprawdził\tautopilot ok");
        parts.add("odczytów: " + st.reads + "\todcz. " + st.reads);
        if (st.ownOffers > 0 && !(st.matchedItems == 1 && st.matchedOffers == 0)) parts.add("Twoich: " + st.ownOffers);
        return new View(GREEN, "działa", parts, st.reads > 0 && now - st.lastReadAt < FLASH_MS);
    }

    /** Stan na ekranie gry (BK pracuje, okno zamknięte). */
    static View hud(long now, AnalizaConfig cfg, Autopilot ap, MarketObserver.Status st, long msToCycle, int pending) {
        List<String> parts = new ArrayList<>();
        boolean apOn = ap != null && ap.enabled();
        if (apOn) {
            String a = "autopilot" + (cfg.apTrial ? " (próba)" : "");
            if (pending <= 0) parts.add(a + ": brak przedmiotów\t" + a + ": pusto");
            else if (msToCycle < 60_000) parts.add(a + ": skan wszystkich zaraz (" + pending + ")\t" + a + ": skan zaraz (" + pending + ")");
            else parts.add(a + ": skan wszystkich za " + msToCycle / 60_000 + " min (" + pending + ")\t" + a + " za " + msToCycle / 60_000 + " min (" + pending + ")");
        }
        if (!MarketObserver.active(cfg, ap)) return new View(GRAY, "nie obserwuje rynku", parts, false);
        if (st.errorAt > st.lastReadAt && now - st.errorAt < 60_000) return new View(RED, "błąd odczytu", parts, false);
        if (st.reads == 0) return new View(YELLOW, "czeka na okno rynku", parts, false);
        long age = now - st.lastReadAt;
        return new View(age < HUD_STALE_MS ? GREEN : YELLOW, "odczyt " + ago(age), parts, age < FLASH_MS);
    }

    /** "1 oferta", "2 oferty", "5 ofert", "22 oferty", "12 ofert". */
    static String ofert(int n) {
        int d = n % 10, dd = n % 100;
        if (n == 1) return "oferta";
        if (d >= 2 && d <= 4 && (dd < 12 || dd > 14)) return "oferty";
        return "ofert";
    }

    static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 2) return "przed chwilą";
        if (s < 60) return s + " s temu";
        if (s < 3600) return s / 60 + " min temu";
        return s / 3600 + " h temu";
    }

    // ---------- rysowanie ----------

    /** Przy oknie rynku: w lewym marginesie obok okna (nie zasłania ofert). */
    public static void renderScreen(Screen screen, DrawContext ctx) {
        AnalizaConfig cfg = RynekAnalizaMod.config();
        if (cfg == null || !cfg.showBadge) return;
        if (!(screen instanceof HandledScreen<?> hs) || !MarketAnalyzer.isMarket(hs)) return;
        View v = market(System.currentTimeMillis(), cfg, RynekAnalizaMod.autopilot(), BkBridge.available(), BkBridge.error(),
                MarketAnalyzer.isRunning(), MarketAnalyzer.isCycle(), MarketObserver.status, MarketObserver.currentWindowRead());
        draw(ctx, MinecraftClient.getInstance().textRenderer, 4, 4, marginWidth(screen.width), v, false);
    }

    /** Ile miejsca jest w lewym marginesie obok okna rynku. */
    static int marginWidth(int screenWidth) {
        int left = (screenWidth - CONTAINER_W) / 2;
        return Math.max(40, Math.min(180, left - 8));
    }

    /** Na ekranie gry: tylko gdy BK pracuje, okno jest zamknięte, a HUD nie jest ukryty (F1). */
    public static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        AnalizaConfig cfg = RynekAnalizaMod.config();
        if (!hudAllowed(cfg, mc.currentScreen != null, mc.options.hudHidden, MarketAnalyzer.isRunning(), BkBridge.isRunning())) return;
        View v = hud(System.currentTimeMillis(), cfg, RynekAnalizaMod.autopilot(), MarketObserver.status, RynekAnalizaMod.msToNextCycle(),
                RynekAnalizaMod.pendingScans());
        draw(ctx, mc.textRenderer, 4, 4, Math.max(120, Math.min(420, ctx.getScaledWindowWidth() - 8)), v, true);
    }

    static boolean hudAllowed(AnalizaConfig cfg, boolean screenOpen, boolean hudHidden, boolean analysis, boolean bkRunning) {
        return cfg != null && cfg.showBadge && !screenOpen && !hudHidden && !analysis && bkRunning;
    }

    /**
     * Rysuje znaczek w (x, y), najwyżej maxW szerokości. inline = jedna linia (stan i reszta po " | "),
     * inaczej: stan w pierwszej linii, reszta pod spodem (najwyżej 4 linie). Zwraca {szerokość, wysokość}.
     */
    static int[] draw(DrawContext ctx, TextRenderer tr, int x, int y, int maxW, View v, boolean inline) {
        int textW = maxW - 17;
        String head = firstThatFits(tr, textW, "Rynek Analiza: " + v.state, "RA: " + v.state, v.state);
        List<String> lines = new ArrayList<>();
        if (inline) {
            for (String p : v.parts) {
                for (String variant : p.split("\t")) {
                    String next = head + " | " + variant;
                    if (tr.getWidth(next) <= textW) {
                        head = next;
                        break;
                    }
                }
            }
        } else {
            lines = pack(tr, v.parts, maxW - 8, MAX_LINES);
        }
        head = ellipsize(tr, head, textW);
        int w = 14 + tr.getWidth(head) + 3;
        for (String l : lines) w = Math.max(w, 4 + tr.getWidth(l) + 4);
        w = Math.min(w, maxW);
        int h = 13 + (lines.isEmpty() ? 0 : lines.size() * 10 + 2);

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(0f, 0f, 300f); // nad przedmiotami, pod podpowiedzią
        try {
            ctx.fill(x, y, x + w, y + h, BG);
            ctx.fill(x, y, x + 1, y + h, v.color); // pasek z lewej w kolorze stanu
            dot(ctx, x + 4, y + 3, v.color, v.flash);
            ctx.drawTextWithShadow(tr, head, x + 14, y + 3, v.color);
            for (int i = 0; i < lines.size(); i++) ctx.drawTextWithShadow(tr, lines.get(i), x + 4, y + 14 + i * 10, i == 0 ? LIGHT : GRAY);
        } finally {
            m.pop();
        }
        return new int[]{w, h};
    }

    /** Okrągła kropka 7x7; przy świeżym odczycie jaśniejsza i z poświatą. */
    static void dot(DrawContext ctx, int x, int y, int color, boolean flash) {
        if (flash) {
            int glow = (color & 0xFFFFFF) | 0x50000000;
            ctx.fill(x - 1, y + 1, x + 8, y + 6, glow);
            ctx.fill(x + 1, y - 1, x + 6, y + 8, glow);
            color = brighten(color);
        }
        ctx.fill(x + 1, y, x + 6, y + 7, color);
        ctx.fill(x, y + 1, x + 7, y + 6, color);
    }

    static int brighten(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r += (255 - r) / 2;
        g += (255 - g) / 2;
        b += (255 - b) / 2;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Części tekstu po kolei w linie (", " między nimi, po dwukropku spacja), najwyżej maxLines linii.
     * Część z krótszą wersją (po tabulatorze): w nowej linii pierwsza wersja, która się mieści; za długa jest skracana "...".
     */
    static List<String> pack(TextRenderer tr, List<String> parts, int w, int maxLines) {
        List<String> lines = new ArrayList<>();
        String cur = null;
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            String[] variants = p.split("\t");
            String sep = cur != null && cur.endsWith(":") ? " " : ", ";
            if (cur != null && tr.getWidth(cur + sep + variants[0]) <= w) {
                cur = cur + sep + variants[0];
                continue;
            }
            if (cur != null) lines.add(cur);
            if (lines.size() >= maxLines) return lines;
            cur = ellipsize(tr, firstThatFits(tr, w, variants), w);
        }
        if (cur != null && lines.size() < maxLines) lines.add(cur);
        return lines;
    }

    static String firstThatFits(TextRenderer tr, int w, String... variants) {
        for (String v : variants) if (tr.getWidth(v) <= w) return v;
        return variants[variants.length - 1];
    }

    static String ellipsize(TextRenderer tr, String s, int w) {
        if (tr.getWidth(s) <= w) return s;
        return tr.trimToWidth(s, Math.max(0, w - tr.getWidth("..."))).stripTrailing() + "...";
    }
}
