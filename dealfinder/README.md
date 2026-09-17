# Łowca Okazji

Wpisujesz, czego szukasz. Aplikacja odpytuje kilka serwisów naraz, odsiewa
śmieci i układa listę **od najtańszej oferty, licząc cenę z dostawą**.
Zapisane wyszukiwania pilnuje sama i woła, gdy coś stanieje.

Wszystko chodzi u Ciebie na komputerze. Nic nie wychodzi do żadnej chmury,
baza to jeden plik SQLite w Twoim katalogu domowym.

```
 1. 2 700,00 zł  [do negocjacji] [używane]
    iPhone 15 128GB czarny, gwarancja do 2027
    OLX · Warszawa, Mazowieckie · https://www.olx.pl/d/oferta/...

 2. 2 769,00 zł  (+69,00 zł)  [w tym dostawa 19,99 zł] [nowe]
    iPhone 15 128GB Black
    ALLEGRO · Gdańsk · https://allegro.pl/oferta/...

Mediana ceny: 2 950,00 zł   ofert po odsianiu: 23
  · Allegro pominięte - brak klucza API. Włączysz je: lowca ustaw --allegro-id ...
  ✓ OLX: 47 → 12 po odsianiu
  ✓ Allegro Lokalnie: 18 → 2 po odsianiu
  ✗ Vinted: www.vinted.pl odmówił dostępu (HTTP 403)
```

## Uruchomienie

Potrzebny jest Python 3.11 lub nowszy — [python.org](https://www.python.org/downloads/)
(na Windowsie przy instalacji zaznacz **Add Python to PATH**).

| System | Polecenie |
|---|---|
| macOS / Linux | `./start.sh` |
| Windows | kliknij dwukrotnie `start.bat` |

Za pierwszym razem skrypt sam zbuduje środowisko i pobierze dwie biblioteki.
Potem otwiera przeglądarkę na `http://127.0.0.1:8777`. Do terminala nie musisz
wracać: klucze Allegro, grupy z Facebooka i wybór źródeł ustawisz w zakładce
**Ustawienia**.

### Najpierw zobacz, czy działa

Zanim zaczniesz szukać przyczyn w konfiguracji albo blokadach serwisów:

```bash
./start.sh serwer --pokaz
```

Tryb pokazowy podstawia przykładowe oferty zamiast prawdziwej sieci, ale
przepuszcza je przez **ten sam** filtr, ranking i deduplikację co normalne
wyszukiwanie. Jeśli tu wszystko wygląda dobrze, program działa — problem jest
w sieci albo w konfiguracji, nie w kodzie.

## Polecenia

```bash
./start.sh szukaj iphone 15 128gb --cena 2000-3200 --miasto Warszawa
./start.sh szukaj rower gorski --bez damski dzieciecy --stan uzywane
./start.sh szukaj ps5 --odrzucone          # pokaż też odsiane i powody
./start.sh szukaj ps5 --sortuj dopasowanie # albo: cena (domyślnie), data

./start.sh obserwuj "ps5 slim" --cena -2000
./start.sh obserwowane                     # lista
./start.sh sprawdz                         # co nowego od ostatniego razu
./start.sh pilnuj --co 30m                 # pilnuje sam i woła powiadomieniem
./start.sh zapomnij 3

./start.sh doktor --zrzut                  # które źródła dziś działają
./start.sh ustaw --pokaz                   # co jest ustawione
```

`pilnuj` chodzi w kółko i przy każdej zmianie wysyła powiadomienie systemowe
(macOS, Linux z `notify-send`, Windows). Zamknięcie okna kończy pilnowanie —
to narzędzie lokalne, nie usługa w tle. Minimalny odstęp to 5 minut.
Pilnowanie w terminalu i przeglądarka mogą chodzić naraz — baza jest w trybie
WAL, więc jedno nie blokuje drugiego.

Wolisz zainstalować to na stałe? `pip install .` daje polecenie `lowca`,
działające tak samo jak `./start.sh`.

## Skąd bierze oferty

| Źródło | Sposób | Uwagi |
|---|---|---|
| **Allegro** | oficjalne REST API | wymaga darmowego klucza, patrz niżej |
| **OLX** | endpoint JSON ich strony, zapasowo parsowanie wyników | brak publicznego API |
| **Allegro Lokalnie** | parsowanie strony wyników | brak API |
| **Vinted** | endpoint katalogu | głównie ubrania |
| **Sprzedajemy.pl** | parsowanie strony wyników | wyłączone domyślnie, włącz `--zrodla` |
| **Facebook** | **gotowe linki, bez automatu** | patrz „Facebook" niżej |

Serwisy odpytywane są **wolno** — domyślnie jedno zapytanie na 1,5 sekundy na
serwis, z normalnym User-Agentem, który mówi czym jest. To narzędzie do
szukania sobie roweru, nie do zdejmowania cudzych serwerów. Gdy serwis
odpowie 403, dostawca się poddaje i wyszukiwanie leci dalej bez niego — nie
ma tu żadnego obchodzenia blokad ani podszywania się.

### Allegro API

Jedyne źródło z błogosławieństwem serwisu, więc warto je mieć włączone.

1. Wejdź na [apps.developer.allegro.pl](https://apps.developer.allegro.pl/) i
   zaloguj się zwykłym kontem Allegro.
2. **Zarejestruj nową aplikację** → typ: *aplikacja bez dostępu do konta
   użytkownika* (REST API).
3. Skopiuj `Client ID` i `Client Secret`.
4. Wpisz je u siebie:

```bash
./start.sh ustaw --allegro-id TWOJE_ID --allegro-sekret TWÓJ_SEKRET
```

Sekret ląduje w `~/.lowca-okazji/config.json` z prawami `600` i nigdzie
stamtąd nie wychodzi. Bez klucza Allegro jest po prostu pomijane — pokaże się
jako uwaga, nie jako awaria.

### Facebook

Marketplace i grupy **nie są przeszukiwane automatycznie** i nie będą.
Wymagałoby to zalogowanej sesji, łamie regulamin Facebooka i realnie kończy
się blokadą konta — Twojego, nie mojego. Zamiast tego aplikacja buduje gotowe
linki wyszukiwania: klikasz, jesteś już zalogowany, widzisz wyniki. Grupy,
które Cię interesują, dopisujesz raz:

```bash
./start.sh ustaw --grupa-fb 123456789 kupie-sprzedam-warszawa
```

Identyfikator to fragment adresu `facebook.com/groups/<TO_TUTAJ>/`.

## Co aplikacja odsiewa

Największa wartość jest nie w znajdowaniu ofert, tylko w wyrzucaniu tych,
które nie są tym, czego szukasz. Wypadają:

- **ogłoszenia „kupię", „zamienię", „naprawa"** — na OLX to spory kawałek wyników,
- **akcesoria** — etui, szkło, ładowarka, części, gdy sam o nie nie prosiłeś.
  Ale „iPhone 15 + **etui gratis**" zostaje: to telefon z dodatkiem, nie etui;
- **przynęty cenowe** — oferta za 1 zł przy medianie 2 900 zł,
- **oferty bez wszystkich słów z zapytania** w tytule, przy czym „128 GB"
  i „128GB" znaczą to samo,
- **duplikaty** — ta sama rzecz wystawiona na OLX i Allegro Lokalnie naraz;
  zostaje tańszy egzemplarz.

Powód odrzucenia każdej oferty widać: w przeglądarce pod „Odsiane", w
terminalu po dodaniu `--odrzucone`. Jeśli filtr wyrzuca za dużo, od razu
widać dlaczego.

## Gdy źródło przestanie działać

Serwisy bez API zmieniają się bez uprzedzenia. Diagnoza to jedno polecenie:

```bash
./start.sh doktor --zrzut
```

Sprawdza każde źródło osobno — łącznie ze ścieżką zapasową OLX — i zapisuje
surowe odpowiedzi do `~/.lowca-okazji/zrzuty/`. Ten zrzut wystarczy, żeby
naprawić parser; same selektory siedzą w `src/dealfinder/providers/`, po
jednym pliku na serwis.

OLX ma ścieżkę zapasową: gdy endpoint JSON przestanie odpowiadać, parser
przechodzi na zwykłą stronę wyników. Parsowanie HTML nie opiera się na
klasach CSS (`providers/html_cards.py`) — szuka linku do oferty i najbliższej
ceny obok niego, więc przeżywa przemalowanie strony.

## Dane z serwisów są traktowane jak wrogie

Tytuł, cena i adres oferty pochodzą z ogłoszenia, które ktoś obcy napisał.
Wystawienie ogłoszenia z linkiem `javascript:...` jest trywialne, a
escapowanie HTML-a przed tym **nie** chroni — link byłby poprawny, tylko
wykonywałby cudzy kod po kliknięciu.

Dlatego adres inny niż `http://` albo `https://` jest odrzucany w trzech
miejscach: przy wyciąganiu z HTML-a, przy tworzeniu oferty (`models.safe_url`)
i jeszcze raz w przeglądarce. Oferta bez poprawnego adresu wypada z wyników z
widocznym powodem. Wszystkie teksty idą do strony przez escapowanie, żaden
nie trafia wprost do `innerHTML`.

Serwer nasłuchuje wyłącznie na `127.0.0.1`. Sekret Allegro leży w pliku z
prawami `600` i nie wraca do przeglądarki żadną drogą — zakładka Ustawienia
dostaje tylko informację, że jest ustawiony.

## Czego to nie robi

Żeby nie było niespodzianek:

- **Jedna strona wyników na źródło.** Allegro, OLX i Vinted potrafią posortować
  po cenie u siebie, więc najtańsze i tak są na pierwszej stronie. Allegro
  Lokalnie i Sprzedajemy zwracają swoją domyślną kolejność — jeśli tam akurat
  trafi się okazja na trzeciej stronie, tego nie zobaczysz.
- **Miasto filtrowane u siebie, bez promienia.** Żadne z tych źródeł nie
  przyjmuje promienia tak samo, więc go nie ma — jest tylko dopasowanie nazwy
  miasta plus oferty z wysyłką.
- **Żadne źródło nie odpowiada dłużej niż 45 sekund** — po tym czasie jest
  pomijane, żeby jedno wolne nie trzymało całego wyszukiwania.
- **Facebook tylko linkami**, powody wyżej.

## Dopisanie kolejnego portalu

Serwis bez niespodzianek to jeden wpis, nie nowy plik —
w `src/dealfinder/providers/generic_html.py`:

```python
GRATKA = SiteSpec(
    name="gratka",
    label="Gratka",
    search_url="https://gratka.pl/szukaj?q={phrase}",
    offer_path="/ogloszenie/",
    base_url="https://gratka.pl/",
)
```

Dopisz go do `_HTML_SITES` w `providers/__init__.py` i gotowe.

## Testy

```bash
.venv/bin/python -m pytest                       # 90 testów, bez internetu
python tools/przejdz-ui.py                       # przejście przeglądarką
```

Testy jednostkowe sprawdzają logikę: odsiewanie, ranking, deduplikację,
ścieżkę zapasową OLX, limit czasu na źródło, bazę, pilnowanie, ustawienia,
odporność na spreparowane ogłoszenia i interfejs HTTP — z atrapami w miejscu
sieci. `tools/przejdz-ui.py` uruchamia
tryb pokazowy i przechodzi prawdziwą przeglądarką przez wyszukiwanie,
sortowanie, obserwowanie, wykrywanie przecen, usuwanie i zapis ustawień;
wymaga `pip install playwright && playwright install chromium`.

**Czego testy NIE sprawdzają:** że OLX, Allegro Lokalnie czy Vinted
odpowiadają dziś dokładnie tak, jak zakłada parser. Odpowiedzi w testach są
odtworzone ze znanego kształtu API, nie zrzucone z żywego serwisu — sesja, w
której powstał ten kod, miała odcięty dostęp do tych domen. Pierwsze
prawdziwe sprawdzenie to `./start.sh doktor` u Ciebie.

## Gdzie co leży

```
src/dealfinder/
  models.py       wspólny kształt oferty, parsowanie cen, normalizacja
  query.py        zapytanie użytkownika
  relevance.py    odsiewanie śmieci
  rank.py         deduplikacja i kolejność
  engine.py       spina wszystko, odpytuje źródła równolegle
  watcher.py      pilnowanie w kółko
  notify.py       powiadomienia systemowe
  demo.py         dane pokazowe (udają tylko źródła, nie potok)
  storage.py      SQLite: obserwowane, historia cen
  net.py          HTTP: limit tempa, powtórki, zrzuty
  cli.py          wiersz poleceń
  providers/      po jednym pliku na serwis
  web/            lokalny serwer i interfejs
tools/
  przejdz-ui.py   test interfejsu prawdziwą przeglądarką
```

Dane: `~/.lowca-okazji/` (`LOWCA_HOME` zmienia lokalizację).
