# Rynek Analiza - dodatek do BK-Rynek 3.0 (Minecraft 1.21.4, Fabric)

Osobny mod, który analizuje rynek i wpisuje ceny prosto w listę przedmiotów BK-Rynek:
- **sprzedaż** = średnia 3 najtańszych ofert za sztukę x ilość sprzedaży z BK,
- **skup** (za sztukę) = cena rynku minus **marża zależna od ceny** - im droższy przedmiot, tym marża z innego progu
  (domyślnie: do 100$ 8,5%, do 1 000$ 33%, do 15 000$ 5%, drożej 9% - np. 70$ -> 64$, 300$ -> 201$, 9 999$ -> 9 499$,
  22 000$ -> 20 020$). Zawsze niżej niż sprzedaż. Progi zmienisz w Ustawieniach, a przedmiot z **Auto: NIE** (pasek pod wykresem)
  zostaje przy Twoim skupie.

**Autopilot** (domyślnie wyłączony) co 12 minut skanuje **wszystkie przedmioty z listy BK** i po każdym skanie ustala sprzedaż
(tuż pod najtańszą cudzą ofertą) i skup (jak wyżej). Gdy BK kupi ten sam przedmiot 3 razy (w 3 transakcjach), ten przedmiot jest
skanowany ponownie od razu. Po włączeniu najpierw działa w **trybie próbnym**: wszystko liczy, ale niczego nie wpisuje do BK. Opis niżej.

Nowość w 0.2.4: skan wszystkich przedmiotów co 12 min, skup z progów marży (rynek + wykres) po każdym skanie - także dla drogich
przedmiotów i bez czekania na 5 analiz, ponowny skan po 3 zakupach. W 0.2.3: analiza wpisuje też skup. W 0.2.1: **znaczek
"Rynek Analiza: działa"** obok okna rynku - zielona kropka miga przy każdym oknie, które mod odczytał (opis w "Podgląd na rynku").

Wszystko było kompilowane i testowane tylko na atrapach API Minecrafta i na symulowanym rynku (prawdziwy build zrobi dopiero
GitHub Actions); mod w tej wersji nie był uruchomiony w grze.

## Jak zbudować
1. Repo z plikami budowania (`build.gradle`, `gradle.properties`, `settings.gradle`, `.github/workflows/build.yml`)
   i folderem `src/` z tej paczki. Build leci w zakładce Actions, jar jest w artefakcie "rynek-analiza".
2. Jar wrzuć do `mods/` razem z BK-Rynek (stary jar tego moda usuń).

## Panel (GUI)
- Klawisz **prawy Shift** (domyślnie) otwiera/zamyka panel. Klawisz **F8** (domyślnie) = start/stop analizy.
  Oba klawisze zmienisz w panelu, w zakładce **Ustawienia** (kliknij przycisk i naciśnij klawisz; Delete/Backspace = brak, Esc = anuluj),
  albo w Sterowaniu Minecrafta (kategoria "Rynek Analiza").
- Zakładka **Przedmioty**: wszystkie przedmioty z aktywnego profilu BK na **jednej długiej liście**, którą przewijasz kółkiem myszy,
  paskiem po prawej (uchwyt można przeciągać, klik w tor przeskakuje) albo klawiszami **PageUp / PageDown / Home / End**
  (gdy nie piszesz w polu tekstowym). Nad listą jest wyszukiwarka i przyciski **Start analizy** (z wpisaną frazą analizuje tylko pasujące) i **Stop**.
  W każdym wierszu: nazwa (z ikoną przy szerszym oknie), mały wykres **Trend** (ostatnie skany, przy szerokim oknie),
  **Śr. 3** (średnia 3 najtańszych ofert za sztukę z ostatniej analizy), **Sprzedaż** (cena sprzedaży w BK, wpisuje ją analiza;
  "551$/16" = 551$ za wystawkę 16 szt.), pole **Skup ($)** (cena skupu w BK za sztukę - patrz niżej) i przycisk **Wykres**.
- Przycisk **Wykres** rozwija wykres tego przedmiotu **pod wierszem** (lista się wydłuża, możesz mieć rozwiniętych kilka naraz).
  Zwijasz go przyciskiem **Ukryj** (w wierszu albo w nagłówku wykresu - ten drugi jest zawsze w zasięgu, nawet gdy wiersz wyjedzie z widoku).
  Po rozwinięciu lista sama przewija się tak, żeby wykres był widoczny. Osobnej zakładki z wykresami już nie ma.
- W rozwiniętym bloku, pod wykresem, jest **pasek autopilota**: przełącznik **Auto: TAK/NIE** (czy autopilot może zmieniać ceny tego
  przedmiotu), pole **Min. sprzedaż** (cena całej wystawki, poniżej której autopilot nie zejdzie), pole **Max skup** (za sztukę, powyżej
  którego autopilot nie podniesie skupu) i linia stanu: marża skupu (z progu ceny), ceny wyliczone przez autopilota i powód ostatniej decyzji.
  Na wykresie decyzje autopilota to przerywane linie: zielona = sprzedaż, fioletowa = skup (za sztukę).
  W małym oknie blok bywa wyższy niż lista - wtedy po rozwinięciu widać nagłówek i wykres, a pasek po przewinięciu w dół.
- Zakładka **Autopilot**: włączanie, tryb (próba / na żywo), kiedy następny skan wszystkich, co czeka na ponowny skan po zakupach,
  statystyki dnia, progi skupu i dziennik decyzji (opis niżej).
- Zakładka **Ustawienia**: klawisze, szybkość analizy (Bezpieczna / Szybka / Maksymalna), **minimalna liczba ofert do ceny** (1-3, domyślnie 3),
  podatek od sprzedaży (0.97 - tylko do pokazania zysku na wykresie),
  pauza na ostatniej stronie, podgląd na rynku, wznawianie BK, ścisłe sprawdzanie komponentów,
  historia cen (zapis, obserwacja rynku podczas pracy BK, odstęp zapisów), **"Wykresy: też obserwacje BK"**, **progi skupu**
  (w jednym wierszu dwa pola: cena "do X$" i marża tego progu w %; ostatni wiersz = marża drożej niż próg 3) oraz liczby autopilota
  (skan wszystkich co ile minut, ponowny skan po ilu zakupach, budżet, limit niesprzedanych zakupów, minimalny zysk, spadek = ofert pod Tobą).
  Ustawienia są na kilku stronach (przyciski < >); przy wąskim oknie niektóre etykiety są skrócone.
- Podczas analizy panel nie otwiera się (komunikat w czacie) - zatrzymaj analizę klawiszem start/stop.
- Ostatnie pomiary i ustawienia są zapisywane w `config/rynek-analiza.json`, stan autopilota w `config/rynek-analiza-autopilot.json`.

## Autopilot
**Włączanie.** Zakładka Autopilot -> "Włącz autopilota". Startuje w trybie **PRÓBNYM**. Przejście na **NA ŻYWO**: przycisk "Tryb: PRÓBNY"
i drugi klik w ciągu 5 s (pierwszy tylko pyta "Na żywo? Kliknij ponownie"). Powrót do próby i wyłączenie - jednym klikiem; wtedy kupowanie
wstrzymane przez autopilota (budżet / limit) wraca w BK.

**Kiedy skanuje.** Co **12 minut** (ustawienie "Skan wszystkich co (min)", liczone od startu poprzedniego skanu) - **wszystkie
przedmioty z listy BK**, także te z Auto: NIE (ich ceny zostają, ale wykres dostaje nowy punkt). Najpierw idą przedmioty czekające
na ponowny skan, potem jeszcze niesprawdzane, potem najdawniej sprawdzane. Po skanie BK pracuje co najmniej 2 minuty - nawet gdy
skan długiej listy trwał dłużej niż odstęp.
**Ponowny skan po zakupach:** gdy BK kupi ten sam przedmiot **3 razy** (3 osobne transakcje) od jego ostatniego skanu, autopilot
skanuje go od razu sam (najwcześniej 30 s po poprzednim skanie), bez czekania na 12 minut. Na czacie: "...: 3 zakupy od ostatniego
skanu - ponowny skan." Liczbę zakupów zmienisz w Ustawieniach ("Autopilot: ponowny skan po zakupach", 0 = wyłączone).
Skan rusza tylko wtedy, gdy **BK pracuje** i nie masz otwartego innego okna (np. ekwipunku albo tego panelu). Na czas skanu BK jest
zatrzymywany i potem zawsze wznawiany. Skan jest cichy: po skanie wszystkich na czacie jest jedna linia, np. "Autopilot: skan
wszystkich (25 przedm.) - wpisane do BK: sprzedaż 3, skup 7" (każda zmiana osobno - w dzienniku w zakładce Autopilot); ponowny skan
i obniżki między skanami piszą każdą zmianę osobno. Do tego podejrzane oferty, budżet i podsumowanie próby; reszta trafia do logu gry
(`latest.log`, linie `[rynek-analiza] autopilot:`). Kiedy następny skan i co czeka na ponowny - zakładka Autopilot i znaczek na ekranie gry.
Między skanami autopilot czyta okna rynku, które otwiera sam BK (nic nie klika): gdy co najmniej 2 cudze oferty są tańsze od Twojej,
obniża cenę sprzedaży (i skup za nią) od razu, bez czekania na skan.

**Tryb próbny.** Liczy ceny sprzedaży i skupu, ale nic nie wpisuje do BK. Gdy w oknie BK pojawi się oferta tańsza niż wyliczony skup,
zapisuje "wirtualny zakup", a po 2 h sprawdza, czy sprzedaż po aktualnej cenie dałaby zysk.
W zakładce Autopilot: wirtualne zakupy, ich wynik i liczba zmian cen z dzisiaj. Zwykła analiza (Start / F8) działa w próbie jak dotąd
(sprzedaż ze średniej 3, skup z progów).

**Na żywo.** Dla przedmiotów z **Auto: TAK**:
- cenę sprzedaży wpisuje tym, które mają w BK włączone "Sprzedawanie" (mod niczego w BK nie włącza),
- **skup wpisuje wszystkim** - od pierwszego skanu (bez czekania na 5 analiz), drogim też; przedmiotom z wyłączonym w BK kupowaniem
  też (cena czeka gotowa, a włączenie kupowania zostaje Twoją decyzją),
- zwykła analiza (Start / F8) też oddaje decyzję autopilotowi (zamiast średniej 3),
- nowa cena sprzedaży dotyczy **kolejnych** wystawek BK - oferty, które już wiszą na rynku, zostają po starej cenie.

**Cena sprzedaży** (za całą wystawkę, jak w BK):
- cel = najtańsza **cudza** oferta minus 1$, ale nie niżej niż **podłoga**,
- podłoga = najwyższa z: koszt zakupu / 0.97 x 1.02 (min. zysk 2%), 85% zwykłej ceny (mediana analiz z 7 dni, gdy jest ich min. 5),
  Twoje "Min. sprzedaż". Bez tego wszystkiego - pierwsza analiza przedmiotu (średnia 3). Zanim autopilot przejmie skup, kosztem jest
  Twój skup z BK,
- oferta dużo tańsza od następnej albo od zwykłej ceny (poniżej 65%) to pomyłka - jest pomijana, a na czacie pojawia się ostrzeżenie,
- tylko **jedna** tańsza oferta = czeka (zwykle szybko schodzi); od **2** tańszych obniża (ustawienie "spadek = ofert pod Tobą"),
- spadek o ponad 20% musi się powtórzyć w 2 skanach z rzędu; po spadku pamięta poprzednią cenę i wraca do niej od razu, gdy rynek wróci,
- w górę tylko po pełnym skanie, po 2 potwierdzeniach i najwyżej o 10% naraz; różnice poniżej 1,5% pomija,
- **Twoje oferty** (rozpoznane po nicku w opisie oferty) nie są brane pod uwagę. Gdy nick nie rozpozna żadnej, oferty dokładnie po Twoich
  cenach z ostatniej doby też są pomijane (żeby autopilot nie podcinał sam siebie).

**Cena skupu** - po każdym skanie, ta sama zasada co przy zwykłej analizie (szczegóły w "Cena skupu" niżej): cena rynku (najtańsza
prawdziwa oferta z tego skanu, z wykresem, gdy rynek nagle podskoczył) minus marża progu ceny. W górę i w dół od razu, zmiany poniżej 1%
pomija. Nie wyżej niż sprzedaż po podatku z min. zyskiem i niż Twoje "Max skup".

**Zabezpieczenia** (na żywo):
- **budżet dzienny** 25 000$ na zakupy po cenach autopilota - po jego wyczerpaniu kupowanie tych przedmiotów jest wyłączane w BK do jutra
  (budżet **0 = autopilot nic nie kupuje**, tylko ustawia ceny). Jeden zakup może budżet przekroczyć (np. przedmiot za 20 000$, gdy
  z budżetu zostało 5 000$) - przy drogich przedmiotach ustaw budżet odpowiednio wyżej,
- **hamulec strat**: strata dnia ponad 20% budżetu = to samo,
- **max 3 niesprzedane zakupy** na przedmiot - kupowanie tego przedmiotu wstrzymane, aż coś zejdzie,
- zakupy rozpoznaje z historii BK, sprzedaże z liczników BK (przedmiot po kwocie: cena wystawki albo cena x 0.97).

**Dziennik** (zakładka Autopilot): ostatnie decyzje, najnowsze na górze. Zielone = wpisane do BK, szare = próba,
czerwone = skan nieudany (np. rynek się nie otworzył). Starsze niż dzisiejsze mają datę.

**Starsze ustawienia.** Przy pierwszym uruchomieniu 0.2.4 odstęp skanów ustawia się na 12 minut, a dawne ustawienia kolejki
(najwyżej przedmiotów na skan, zmiana ceny od %, najrzadziej co) i marż autopilota (startowa, min., max, próg "drogich",
marża skupu przy analizie) znikają - skup liczą progi marży. Później możesz odstęp znowu zmienić - zostanie.

**Ograniczenia.** Autopilot nie był sprawdzany w grze. Rozpoznawanie własnych ofert zakłada, że opis oferty na rynku zawiera Twój nick.
Dwie sprzedaże między odczytami (co sekundę) są liczone, ale bez przypisania do przedmiotu; dwa przedmioty z tą samą ceną wystawki
mogą się pomylić przy przypisaniu sprzedaży. Podatek 0.97 bierze z ustawień ("Podatek od sprzedaży").

## Cena sprzedaży (wpisuje analiza)
Analiza (Start / F8) dla każdego przedmiotu liczy średnią 3 najtańszych ofert **za sztukę** i wpisuje do BK `sellPrice`:
`sprzedaż = floor( średnia 3 najtańszych za sztukę x ilość sprzedaży )`.
- **Ilość sprzedaży** to pole "Ilość sprzedaży" wpisu w BK (brak = 1). BK wystawia przedmiot komendą `/wystaw {quantity} {price}`,
  gdzie `{price}` to cena **całej wystawki** - dlatego np. złote jabłka z ilością 64 dostają cenę za 64 szt. (64 x średnia za sztukę).
- Razem ze sprzedażą analiza wpisuje skup (`maxPrice`) - patrz niżej.
- Mod **nie włącza sprzedaży** - w BK przy przedmiocie musi być włączone "Sprzedawanie", a w ustawieniach BK "Automatyczna sprzedaż"
  (w Twoim profilu oba są teraz wyłączone). Sprawdź najpierw na jednym przedmiocie.
- Mniej ofert niż "Min. ofert do ceny" (domyślnie 3) = cena sprzedaży zostaje bez zmian (komunikat w czacie mówi, ile ofert było).
  Przy rzadkich przedmiotach możesz zejść do 2 albo 1 (wtedy jedna oferta wyznacza cenę - ostrożnie).
- Cena sprzedaży zmienia się tylko po analizie. Jeśli zmienisz w BK ilość sprzedaży, uruchom analizę tego przedmiotu ponownie
  (wpisz jego nazwę w wyszukiwarce panelu i kliknij "Start (filtr)").

## Cena skupu
Analiza (Start / F8) i autopilot liczą skup tak samo: **cena rynku minus marża progu ceny** (za sztukę).
- **Cena rynku** = najtańsza prawdziwa cudza oferta z bieżącej analizy (pomyłki - oferty dużo tańsze od następnej - są pomijane).
  Gdy rynek jest drożej niż na **wykresie** (mediana analiz z 7 dni), 30% ceny bierze z wykresu, żeby nie gonić chwilowego skoku
  (np. rynek 11 000$, wykres 10 000$ -> 10 700$). Gdy nikt nie sprzedaje - sama cena z wykresu.
- **Marża progu** - im droższy przedmiot, tym marża z innego progu (Ustawienia, wiersze "Skup: próg ..."):

  | cena za sztukę | marża | przykład |
  |---|---|---|
  | do 100$ | 8,5% | złote jabłko 70$ -> skup 64,05$ |
  | do 1 000$ | 33% | diamentowa część 300$ -> 201$ |
  | do 15 000$ | 5% | Anarchiczna I 9 999$ -> 9 499$ |
  | drożej | 9% | Anarchiczna II 22 000$ -> 20 020$ |

  Próg "do X$" obejmuje X (100$ = jeszcze 8,5%). Próg 0 = wyłączony. Marża jest przycinana do 0-90%.
- Skup **nigdy nie jest wyżej niż sprzedaż po podatku z min. zyskiem** (`sprzedaż x 0.97 / 1.02`) - przy małej marży to on decyduje.
- Nie wyżej niż "Max skup" z paska przedmiotu (pod wykresem).
- Przedmiot z **Auto: NIE** zostaje przy swoim skupie ("skup bez zmian (Auto: NIE)") - tak ustalasz skup sam.
- Bez trybu "Unit" w BK skup dotyczy całej oferty "Ilość kupna" sztuk - mod wpisuje wtedy cenę za tyle sztuk.
- Zmiany mniejsze niż 1% są pomijane ("skup bez zmian (...)"). W komunikacie widać źródło, np. "skup 9499$/szt. (rynek 9999$ - 5.0%)".
- Wyłączenie przy zwykłej analizie: Ustawienia, "Analiza wpisuje też skup". BK kupuje oferty tańsze niż skup (porównanie "mniejsze niż").

Skup możesz też wpisać ręcznie w BK albo w polu **Skup ($)** w panelu (cena za sztukę, jak "Limit kupna" w BK) - przy Auto: TAK
następna analiza go nadpisze.
- wpisana liczba trafia do BK **ok. 1 s po ostatnim wpisanym znaku** (żeby przy wpisywaniu "14000" nie poszły ceny 1, 14, 140...)
  albo od razu po zamknięciu panelu; dolna linia panelu pokazuje "skup 13490$ -> 14000$",
- puste pole albo 0 niczego nie zmienia, przecinek działa jak kropka,
- samo otwarcie i zamknięcie panelu niczego nie zapisuje.

## Wykresy (pod wierszem przedmiotu)
- Każdy przedmiot ma własny wykres ze **wszystkich analiz** (od pierwszej do ostatniej). Jeden punkt = jeden skan: wysokość = **średnia 3 najtańszych ofert za sztukę**.
  Na wykresie: oś cen z siatką (okrągłe wartości), delikatne wypełnienie pod linią, przerywana **linia średniej** ze skanów,
  pogrubiona linia z zaokrąglonymi punktami, nad punktami wartości, pod nimi **godzina skanu** (HH:mm) i data (dd.MM), gdy się zmienia.
  Najnowszy skan to większy biały punkt z niebieską "pigułką" z wartością. Gdy punktów jest dużo, a okno wąskie, podpisy są pokazywane
  co któryś punkt (zawsze ostatni), a przy bardzo wielu analizach zostaje sama linia bez kropek - szczegóły każdego punktu i tak pokaże najechanie myszą.
- Najechanie myszą na wykres pokazuje linię pionową i kartę z szczegółami najbliższego punktu: pełna data i godzina,
  źródło (skan z analizy / obserwacja BK), średnia 3, najtańsza oferta i liczba ofert.
- Pod wykresem cztery karty: **Średnia** (ze średnich 3), **Najniższa**, **Najwyższa** i **Ostatni** (z procentową zmianą względem poprzedniego skanu),
  a pod nimi linia szczegółów: `sprzedaż w BK` (z ilością, gdy > 1), `skup w BK`, `zysk/szt.` (sprzedaż za sztukę x podatek 0.97 - skup),
  `rozstęp`, `najtańsza oferta` (najtańsza pojedyncza oferta ze wszystkich analiz) i `ostatni skan` (ile minut/godzin temu).
  Karty i rozstęp też liczą się ze wszystkich analiz.
  Przy wąskim oknie linia pokazuje tyle, ile się mieści (ceny w BK i zysk są pierwsze).
- Domyślnie punkty pochodzą **tylko ze skanów z analizy**. Przełącznik **"Wykresy: też obserwacje BK"** (zakładka Ustawienia)
  dodaje zaufane pomiary z okien rynku otwieranych przez BK (patrz niżej). Wybór jest zapamiętywany.
- Przedmiot bez skanów pokazuje krótki komunikat zamiast wykresu. Wykresy zapełniają się po kolejnych analizach (Start / F8).

## Wzór
**Cena sprzedaży** (wpisywana do BK jako `sellPrice`) = `floor( średnia 3 najtańszych ofert za sztukę x ilość sprzedaży )`.
**Zysk na sztuce** (tylko pokazywany) = `sprzedaż / ilość x 0.97 - skup`.

## Co dzieje się podczas analizy
BK-Rynek jest zatrzymywany (i wznawiany po analizie, jeśli działał). Dla każdego przedmiotu:
`/ah search <nazwa>` -> PPM w repeater dwa razy ("Najniższej ceny") -> skan -> cena za sztukę z każdej pasującej oferty ->
cena sprzedaży -> `sellPrice` wpisu w BK -> na końcu zapis configu BK i przeładowanie cen. Pomiar (Śr. 3, najtańsza oferta, liczba ofert, czas)
trafia do historii (wykresy).
Mniej ofert niż minimum = cena sprzedaży nie zostaje ustawiona i pomiar nie jest zapisywany.

**Zapasowe wyszukiwanie.** Gdy po `/ah search <pełna nazwa>` na rynku nie było **żadnego** przedmiotu o tym id (albo rynek się nie otworzył),
mod próbuje krótszej frazy: bez numeru na końcu, a potem samo pierwsze słowo - np. "Anarchiczny hełm II" -> "Anarchiczny hełm" -> "Anarchiczny".
Dopasowanie BK i tak wybiera właściwy przedmiot (hełm II, nie hełm I). Gdy przedmioty tego id były, ale nie pasowały, mod nie szuka dalej,
tylko pokazuje diagnostykę (niżej). Własna fraza w `search` w configu wyłącza zapasowe wyszukiwanie dla tego przedmiotu.

**Przedmioty o tej samej nazwie** (np. 5 x "Zaklęta książka", 2 x "Diamentowy miecz") są rozróżniane po pierwszej linii lore
(potem CMD / enchanty) - w panelu widać np. "Zaklęta książka [Regeneracji I]", a każdy ma osobny wykres i pomiar.
Wcześniej ich pomiary się mieszały; ta wspólna stara historia nie jest już pokazywana, nowe wykresy zapełnią się od następnej analizy.
Przedmioty z unikalną nazwą zachowują swoją historię.

Dopasowanie ofert jak w BK: najpierw zgodne id przedmiotu (BK wybiera wpisy po id, np. `enchanted_golden_apple`),
potem `AutoMarketScanner.matchesEntry` (nazwa, enchanty, lore, custom model data...).
Zwykłe złote jabłko o nazwie "Zaklęte złote jabłko" nie przejdzie.

Zasięg skanu zależy od przedmiotu (na stałe, bez ustawienia): przedmioty niestackowalne (po 1 szt., np. hełmy) - skan kończy się,
gdy znaleziono 3 oferty, bo rynek jest posortowany od najniższej ceny; przedmioty stackowalne (np. złote jabłka) -
przeglądane są wszystkie strony, bo większy stack na dalszej stronie może mieć niższą cenę za sztukę.

## Podgląd na rynku
- Nad rynkiem pasek z ikoną i nazwą analizowanego przedmiotu, numerem w kolejce, stroną, liczbą ofert i trybem skanu.
- 3 najtańsze oferty (idą do ceny sprzedaży) mają **żółtą** ramkę. Ostatnia strona skanu z podświetleniem jest chwilę trzymana
  (`highlightPauseTicks`, domyślnie 10 ticków = 0,5 s; 0 = bez pauzy).
- Po policzeniu w czacie: "Użyte oferty: str.1 r1 k1 = 950/szt (id "nazwa" xN); ...". To ostateczny wybór;
  w trybie pełnego skanu podświetlenie na kolejnych stronach pokazuje stan chwilowy.
- Wyłączenie podglądu: zakładka Ustawienia albo `"showOverlay": false`.

### Znaczek "Rynek Analiza: działa"
Pokazuje, że mod naprawdę czyta rynek, gdy skanuje go BK (tylko rysuje - niczego nie klika).
- Przy każdym oknie rynku, w lewym górnym rogu ekranu (obok okna, nie na ofertach):
  - **zielona kropka i "działa"** - mod odczytał okno; kropka mignie przy każdym nowym odczycie. Pod spodem: ile cudzych ofert
    przedmiotu z Twojej listy BK zobaczył (i nazwa), najtańsza za sztukę, "zapisano do wykresu", "autopilot sprawdził",
    licznik odczytów od startu gry i ile było Twoich ofert. Na wąskim ekranie część opisu jest skrócona.
  - **żółty "czytam okno..."** - nowe okno jeszcze nie odczytane. Mod czyta okno najwyżej co 3 s, więc przy szybkim
    przełączaniu stron część pomija; dopóki ostatni odczyt jest sprzed najwyżej 10 s, znaczek zostaje zielony.
  - **czerwony** - mod nie widzi BK albo odczyt się nie udał (z treścią błędu).
  - **szary "nie obserwuje"** - wyłączone "Obserwuj rynek, gdy działa BK" albo "Zapisuj historię cen" (a autopilot też wyłączony).
  - "bez zapisu: nie od najtańszych" jest normalne: okno nie pokazuje najtańszych ofert (np. dalsza strona albo inne
    sortowanie), więc nie idzie do wykresu. "brak przedmiotów z listy BK" = w oknie nie ma niczego z Twojej listy.
  - W trakcie analizy (Twojej albo skanu autopilota) znaczek mówi tylko "analiza trwa" / "skan autopilota" - szczegóły są nad oknem.
- Gdy BK pracuje, a okno rynku jest zamknięte: jedna linia w lewym górnym rogu ekranu gry - kiedy był ostatni odczyt
  i co z autopilotem (gdy włączony): "skan wszystkich za 8 min (25)" = za 8 minut skan 25 przedmiotów (na wąskim ekranie
  "za 8 min (25)"), "brak przedmiotów" = lista BK jest pusta.
  Chowa się pod F1.
- Wyłączenie: zakładka Ustawienia, "Znaczek 'działa'" (albo `"showBadge": false`).

## Komendy (opcjonalnie)
`/analiza start [fragment]`, `/analiza stop`, `/analiza lista` (nazwa, skup, sprzedaż i ilość każdego przedmiotu).

## Do sprawdzenia przy pierwszym teście autopilota
1. Włącz autopilota (tryb próbny) i zostaw BK włączony. Zaraz po zamknięciu panelu sprawdzi wszystkie przedmioty, potem co 12 minut.
   W zakładce Autopilot pojawią się decyzje (sprzedaż i skup z marżą progu), a przy przedmiotach - linia stanu w pasku pod wykresem.
   W BK nic się nie powinno zmienić. Ceny trafiają do BK dopiero w trybie **NA ŻYWO**.
2. W logu analizy (i na znaczku przy rynku: "Twoich: N") powinno być widać Twoje oferty, jeśli masz coś wystawione. Jeśli masz oferty, a tego dopisku brak - nick nie jest
   rozpoznawany: daj znać, zanim przełączysz na żywo.
3. Po dniu próby sprawdź wynik wirtualnych zakupów. Na żywo zacznij od niskiego budżetu i kilku przedmiotów (resztę przełącz na Auto: NIE).

## Do sprawdzenia przy pierwszym teście
1. Prawy Shift otwiera panel i widać w nim przedmioty z BK (jeśli nie - komunikat błędu w panelu).
2. Analiza jednego przedmiotu (wpisz nazwę w wyszukiwarce panelu, "Start (filtr)"): w czacie "średnia 3 najtańszych = X/szt. -> sprzedaż Y$ za N szt.",
   w kolumnie "Sprzedaż" ta sama cena, a w BK "Cena sprzedaży" tego przedmiotu też. Skup w BK = cena rynku minus marża progu
  (w czacie: "skup X$/szt. (rynek Y$ - Z%)").
3. Złote jabłka (ilość sprzedaży 64): cena sprzedaży = 64 x średnia za sztukę. **Zanim włączysz w BK sprzedawanie, sprawdź na jednym przedmiocie,
   że wystawka na rynku ma właściwą cenę.**
4. Anarchiczny hełm II: jeśli dalej "nie znaleziono żadnej pasującej oferty", w czacie pojawią się linie "BK: ..." z powodem odrzucenia,
   a pełny raport BK będzie w `config/rynek-analiza-diagnostyka.txt` - przyślij go. Jeśli "na rynku nie było żadnego przedmiotu o id..." także po
   zapasowych frazach - na rynku po prostu nie ma ofert albo serwer szuka inną frazą (wpisz ją w `search` w configu).
5. Fraza po `/ah search` to domyślnie nazwa przedmiotu bez kolorów. Jeśli serwer szuka inaczej, wpisz własną w `search` w configu.
6. Cena z lore: używany jest `lore_regex` z profilu BK, a gdy go brak - pierwsza liczba po słowie "Koszt".
7. BK porównuje cenę za sztukę tylko przy włączonym trybie "Unit" w profilu.

## Szybkość analizy
Zamiast stałych opóźnień bot czeka na faktyczną odpowiedź serwera: po każdym kliknięciu obserwuje okno rynku i idzie dalej,
gdy dane przestały się zmieniać (kilka ticków). Dodatkowo: nie klika sortowania, jeśli "Najniższej ceny" jest już ustawione,
pauza z podświetleniem jest tylko raz na przedmiot, a przerwa między przedmiotami jest krótka.
Poziomy (zakładka Ustawienia): Bezpieczna (4 ticki między kliknięciami, 3 ticki spokoju), Szybka - domyślna (2 / 2), Maksymalna (1 / 1).
Jeśli serwer zacznie odrzucać komendy lub klikanie, wybierz niższy poziom. Rzeczywisty czas zależy od pingu i serwera.

## Dopasowanie i cena - jak w BK
- Cena oferty jest czytana funkcją BK (`AutoMarketScanner.extractPrice`, ten sam skaner co w silniku BK): pomija m.in. linie
  "koszt użycia" w lore przedmiotów customowych. Własny odczyt ("Koszt" / `lore_regex`) działa tylko jako zapas.
- Liczba komponentów NIE jest sprawdzana domyślnie (BK sam jej nie sprawdza w swojej ścieżce dopasowania, więc dodatkowe
  sprawdzenie mogło odrzucać prawidłowe oferty). Włączysz je w Ustawieniach ("Ścisłe sprawdzanie komponentów").
- Gdy dla przedmiotu nie znaleziono dość ofert, w czacie pojawia się linia "Diagnostyka": ile przedmiotów o tym id było w oknie,
  ile odrzuciło dopasowanie BK (z przykładową nazwą), ile liczba komponentów, ile nie miało odczytanej ceny - albo że przedmiotów
  tego id w ogóle nie było.
- Gdy BK odrzucił oferty, mod prosi BK o jego własny raport dopasowania (`CleanMarketEngine.debug`) dla pierwszej odrzuconej oferty:
  kilka linii z sekcji FAILURES trafia do czatu ("BK: ..."), a cały raport do `config/rynek-analiza-diagnostyka.txt` (dopisywany na końcu).

## Historia cen
Plik: `config/rynek-analiza-history.json`. Zapis w tle co kilka minut, przy końcu analizy i przy zamykaniu gry.
Do ok. 1000 punktów na przedmiot. Ponad to najpierw znika co druga obserwacja; gdy zostały same skany (autopilot skanuje co 12 min,
ok. 120 punktów na dobę), przerzedzana jest starsza połowa - ostatnie dni zostają w pełnej rozdzielczości, a pierwsza analiza
przedmiotu zostaje zawsze.

Źródła punktów:
- **Skan z analizy** (Start / F8): po każdej analizie przedmiotu zapisywany jest pomiar (najtańsza oferta i średnia 3 najtańszych, za sztukę,
  liczba ofert, czas). To jedyne źródło wykresu w ustawieniu domyślnym i najpewniejsze dane.
- **Obserwacja podczas pracy BK** (Ustawienia -> "Obserwuj rynek, gdy działa BK"): gdy BK otworzy okno rynku, mod tylko je czyta
  (nie klika, nie zatrzymuje BK) i zapisuje ceny przedmiotów z listy - ale tylko wtedy, gdy widać prawdziwe minimum (rynek ma jedną stronę
  albo to strona 1 posortowana rosnąco po cenie). Okna przy innym sortowaniu (BK często używa "Najnowsze") nie są już zapisywane.
  Zapis nie częściej niż raz na minutę na przedmiot. Te punkty widać na wykresie po przełączeniu "skany + obserwacja BK".
- Stare punkty-próbki z poprzedniej wersji moda są w pliku, ale nie są pokazywane ani liczone.

## Czego już nie ma
- **Kolejka "tylko zmienione ceny" i nauka marży autopilota** (0.2.2-0.2.3: najwyżej 6 przedmiotów na skan, skan po zmianie ceny o 2%,
  marża startowa/min./max uczona po zakupach, skup drogich przedmiotów dopiero po 5 zyskownych sprzedażach, "Marża skupu przy analizie") -
  zastąpione skanem wszystkich co 12 min i progami marży skupu. Stare pola w configu są pomijane i znikają przy zapisie.
- **Stara marża per przedmiot** (pole "Marża", "Domyślna marża", komendy `/analiza marza` i `/analiza marza_domyslna`) - skup liczy
  teraz progi marży skupu. Stare pola `margins` / `defaultMargin` w configu są pomijane i znikają przy zapisie.
- **Sugerowana marża** (z rozrzutu cen, z historii, przycisk "Użyj") - usunięta w całości razem z mnożnikiem w ustawieniach i pomarańczowymi
  ramkami 4.-5. oferty. Stare pola `suggested` / `suggestFactor` w configu są ignorowane.
- **Profil rynku i podgląd decyzji BUY / WATCH / REJECT** - usunięty razem ze starym wykresem (liczył się z zakresów czasu i zaufanych pomiarów,
  których nowy wykres już nie używa).
