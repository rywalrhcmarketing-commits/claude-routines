# Łowca Okazji

Wpisujesz, czego szukasz. Aplikacja odpytuje kilka serwisów naraz, odsiewa
śmieci i układa listę **od najtańszej oferty, licząc cenę z dostawą**.

Wszystko chodzi u Ciebie na komputerze. Nic nie wychodzi do żadnej chmury,
baza to jeden plik SQLite w Twoim katalogu domowym.

```
 1. 2 700,00 zł  [do negocjacji]
    iPhone 15 128GB czarny, gwarancja
    OLX · Warszawa, Mazowieckie · https://www.olx.pl/d/oferta/...

 2. 2 769,00 zł  (+69,00 zł)  [w tym dostawa 19,99 zł]
    iPhone 15 128GB Black
    ALLEGRO · Gdańsk · https://allegro.pl/oferta/...

Mediana ceny: 2 950,00 zł   ofert po odsianiu: 23
  ✓ OLX: 47 → 12 po odsianiu
  ✓ Allegro: 60 → 9 po odsianiu
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
Potem otwiera przeglądarkę na `http://127.0.0.1:8777`.

Wolisz terminal? Te same polecenia przyjmuje skrypt startowy:

```bash
./start.sh szukaj iphone 15 128gb --cena 2000-3200 --miasto Warszawa
./start.sh szukaj rower gorski --bez damski dzieciecy --stan uzywane
./start.sh obserwuj "ps5 slim" --cena -2000
./start.sh sprawdz          # co nowego od ostatniego razu
./start.sh doktor           # które źródła dziś działają
```

## Skąd bierze oferty

| Źródło | Sposób | Uwagi |
|---|---|---|
| **Allegro** | oficjalne REST API | wymaga darmowego klucza, patrz niżej |
| **OLX** | endpoint JSON ich własnej strony | brak publicznego API dla osób trzecich |
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
stamtąd nie wychodzi.

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

- **ogłoszenia „kupię" i „zamienię"** — na OLX to jedna trzecia wyników,
- **akcesoria** — etui, szkło, ładowarka, części, gdy sam o nie nie prosiłeś
  (wpisz „etui iphone 15" i etui zostaną),
- **przynęty cenowe** — oferta za 1 zł przy medianie 2 900 zł,
- **oferty bez wszystkich słów z zapytania** w tytule,
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

Sprawdza każde źródło osobno i zapisuje surowe odpowiedzi do
`~/.lowca-okazji/zrzuty/`. Ten zrzut wystarczy, żeby naprawić parser —
same selektory siedzą w `src/dealfinder/providers/`, po jednym pliku na
serwis.

Parsowanie HTML ma ścieżkę zapasową (`providers/html_cards.py`): zamiast
sztywnych klas CSS szuka linku do oferty i najbliższej ceny obok niego.
Przeżywa zmianę wyglądu strony, ale bywa mniej dokładne.

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
.venv/bin/python -m pytest
```

33 testy. Sprawdzają logikę: filtrowanie, ranking, deduplikację, bazę i
interfejs HTTP — z atrapami w miejscu sieci, więc chodzą bez internetu.

**Czego testy NIE sprawdzają:** że OLX, Allegro Lokalnie czy Vinted
odpowiadają dziś dokładnie tak, jak zakłada parser. Odpowiedzi w testach są
odtworzone ze znanego kształtu API, nie zrzucone z żywego serwisu — sesja, w
której powstał ten kod, miała odcięty dostęp do tych domen. Pierwsze
prawdziwe sprawdzenie to `./start.sh doktor` u Ciebie.

## Gdzie co leży

```
src/dealfinder/
  models.py       wspólny kształt oferty, parsowanie cen
  query.py        zapytanie użytkownika
  relevance.py    odsiewanie śmieci
  rank.py         deduplikacja i kolejność
  engine.py       spina wszystko, odpytuje źródła równolegle
  storage.py      SQLite: obserwowane, historia cen
  net.py          HTTP: limit tempa, powtórki, zrzuty
  cli.py          wiersz poleceń
  providers/      po jednym pliku na serwis
  web/            lokalny serwer i interfejs
```

Dane: `~/.lowca-okazji/` (`LOWCA_HOME` zmienia lokalizację).
