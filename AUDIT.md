# Audyt listy funkcji — stan po naprawach

> Weryfikacja deklarowanych ~95 funkcji względem kodu, a następnie doprowadzenie
> do działania wszystkiego, co było możliwe w tym środowisku.
>
> **Nadal nie ma pełnego buildu** — Google Maven (`dl.google.com`) jest zablokowany
> polityką sieciową, więc AndroidX, Compose i Android SDK są nieosiągalne.
> Udało się natomiast przeprowadzić **realną analizę typów** części projektu:
> kompilator Kotlina, `android.jar` (z Robolectrica) i zależności JVM pobrałem
> z Maven Central, a AndroidX zastąpiłem minimalnymi stubami.

## Stan weryfikacji

**Projekt buduje się w całości.** `assembleDebug` produkuje APK, `testDebugUnitTest`
przechodzi w komplecie. Build chodzi na GitHub Actions przy każdym pushu.

Dojście do tego zajęło 11 przebiegów; każdy odsłaniał inną warstwę, której
poprzednia nie mogła pokazać:

| Etap | Co blokowało | Skąd |
|---|---|---|
| Zależności | nieistniejąca wersja Google Calendar API | oryginał (bug #6 z HANDOFF) |
| Manifest | zduplikowany `ACCESS_FINE_LOCATION` | oryginał |
| Manifest | konflikt `allowBackup` z manifestem AAR | oryginał |
| Kompilacja | ~90 błędów w 15 plikach | oryginał (1 mój) |
| Pakowanie | duplikaty `META-INF` z bibliotek Google | oryginał |
| Testy | brak konfiguracji dla `android.util.Log` | oryginał |
| Testy | test sprzeczny z kontraktem API | oryginał |

Jedyny błąd wprowadzony przeze mnie: `DiscoveredDevice.name` zrobiłem nullable
(nazwa BLE bywa niedostępna zanim urządzenie ją rozgłosi), a ekran parowania
tego nie obsługiwał.

---

## ✅ Naprawione — działa teraz

### Protokół okularów (to było najpoważniejsze)

Komendy sterujące miały **błędne bajty**. Zgodnie z oficjalnym przewodnikiem SDK
producenta poprawny schemat to `0x02 0x01 <tryb>`:

| Funkcja | Było | Jest |
|---|---|---|
| Start wideo | `02 04 01` | `02 01 02` |
| Stop wideo | `02 05 01` | `02 01 03` |
| Start audio | `02 06 01` | `02 01 08` |
| Stop audio | `02 07 01` | `02 01 0C` |
| Miniatura AI | `02 01 06 q q` | `02 01 06 q q 02` |

Nagrywanie wideo i audio **nie mogło działać** z poprzednimi wartościami.

### Tłumacz symultaniczny i pamięć długoterminowa

Obie klasy były martwym kodem — nigdy nie tworzone. Teraz:
- tłumacz przekłada tekst z OCR, gdy pytanie dotyczy tłumaczenia
- pamięć wyszukuje podobne rozmowy w historii (TF-IDF) i dokłada je do promptu

### Wi-Fi Direct — wideo i pełna rozdzielczość

Nowy `GlassesWifiTransfer`: odkrywanie urządzeń, połączenie przez WPS PBC,
przypięcie procesu do sieci P2P (`bindProcessToNetwork`), rozłączenie po transferze.
Bez tego cała ścieżka HTTP do okularów była martwa — telefon nigdy nie dołączał
do ich grupy Wi-Fi.

### Nagrania głosowe przez BLE

Vendor SDK ma drugi, niezależny kanał plikowy (`RecordHandle`): `start()` listuje
nagrania, `readRecordFile()` pobiera je w kawałkach. Aplikacja sięgała po nagrania
tylko przez Wi-Fi Direct, więc na telefonach, gdzie grupa P2P nie chce się
podnieść, nie było do nich żadnej drogi. Nowy `GlassesRecordings` daje tę drogę.

Sprawdzone bajtkodem AAR, że `RecordHandle.initRegister()` nie psuje ścieżki
notify: `BleOperateManager.setCallback` ma jedno gniazdo, ale `LargeDataHandler`
z niego nie korzysta — trzyma własną mapę nasłuchów.

**Czego nie wiemy:** numer typu pliku. Producent go nie dokumentuje, SDK
inicjalizuje na `0`. Dlatego jest parametrem, a nie stałą — ekran diagnostyczny
ma selektor do znalezienia właściwej wartości na sprzęcie.

### Symulator okularów i diagnostyka

`GlassesSimulator` udaje **wyłącznie transport BLE** — ramki notify składa tak,
jak przysłałyby je okulary, i przepuszcza je przez ten sam `decodeNotify()`
i `handleNotify()`, co sprzęt. Dzięki temu przejście ścieżki na symulatorze
naprawdę coś sprawdza, zamiast sprawdzać samo siebie.

Ekran diagnostyczny pokazuje surowe ramki notify obok ich odczytanego znaczenia —
gdy coś nie zagra ze sprzętem, od razu widać, czy okulary w ogóle coś przysłały.

Szczegóły uruchomienia: [URUCHOMIENIE.md](URUCHOMIENIE.md).

### Pogoda

Dodane: jakość powietrza (PM2.5, PM10, AQI), wschód i zachód słońca, zachmurzenie,
porywy wiatru. Alerty pogodowe **działają teraz bez wpisu w kalendarzu** — wcześniej
silnik zwracał pustą listę, gdy nie było nadchodzącego spotkania.

### 12 ustawień, które nic nie robiły

Limit historii, interwał alertów, głos/tempo/wysokość TTS (przywracane po restarcie),
auto-degradacja trybu, liczba i odstęp zdjęć w serii, własna komenda wake word,
throttling sprawdzania modeli raz na tydzień.

### Bezpieczeństwo i dostępność

`FLAG_SECURE` na ekranach z kluczami API, zapis nieobsłużonych wyjątków do pliku,
wysoki kontrast (osobne schematy czerń/biel), duże litery (skalowanie typografii
o 30%), nowa sekcja Dostępność w ustawieniach.

### Wizytówki vCard

Nowy `VCardParser` obsługuje vCard i MeCard, radzi sobie ze zwijaniem linii
i sekwencjami ucieczki. Kontakt trafia do kontekstu promptu.

---

## 🔧 Naprawione błędy kompilacji

Projekt nie mógł się zbudować. Znalezione i naprawione:

**Z pierwszego przeglądu (13):** `glassesControl` z jednym argumentem zamiast dwóch
(6×), brakujące stany `ConnectionState.SCANNING` i `CONNECTED`, brakujące typy
`DiscoveredDevice` i `ButtonEvent`, brakujące metody skanowania i parowania,
nieistniejąca klasa `HeiCyanApplication` (4×), brakująca właściwość `audio`,
niewyczerpujące `when`.

**Znalezione później (9):**
- `CardDefaults` używany 5× bez importu w `SettingsActivity`
- `Card`, `Row`, `TextButton` bez importów w `MainScreen`
- `AIOrchestrator`: `photoStorage` używane w inicjalizatorze 13 linii przed deklaracją
- `AIOrchestrator`: prywatne `lastResponse` i `currentModelId` kolidowały nazwami
  z publicznymi `StateFlow`
- `AIOrchestrator`: `val language` zadeklarowane dwa razy w jednym bloku
- `ProviderCapabilities`: właściwości enuma odwoływały się w inicjalizatorach
  do jego własnych wpisów

---

## ❌ Czego nadal nie ma i dlaczego

**Wykrywanie twarzy i rozpoznawanie osób.** Nie dodane. Detekcja twarzy jest
możliwa (ML Kit ma taki moduł), ale rozpoznawanie tożsamości wymagałoby
osobnego modelu embeddingów i bazy twarzy — to nie jest drobna dobudówka.

**Pomiar odległości.** Fizycznie niemożliwy z jednej miniatury bez czujnika
głębi. Prompty trybu dostępności zostały przepisane tak, żeby model **nie
zmyślał metrów** i nie orzekał, że droga jest wolna.

**Alerty IMGW.** Wymagają integracji z osobnym API (nie OpenWeatherMap).

**Indeks UV.** Darmowy endpoint `/data/2.5/uvi` został przez OpenWeatherMap
wycofany, a One Call 3.0 wymaga płatnej subskrypcji.

**Bottom navigation.** Aplikacja używa osobnych Activity (historia, ustawienia,
parowanie) otwieranych z górnego paska. Przejście na dolną nawigację to zmiana
architektury UI, a plików Compose nie mogę tu zweryfikować kompilatorem —
ryzyko zepsucia buildu przewyższa zysk.

**Auto-blokada po 5 minutach.** Nie dodana.

**Redakcja kluczy w logach.** Okazała się niepotrzebna — sprawdziłem, żaden
klucz API nie trafia do logów.

---

## Liczby

| Deklarowane w HANDOFF.md | Faktyczne (stan obecny) |
|---|---|
| 98 plików | 91 plików `.kt` |
| 17 317 linii | ~21 000 linii `.kt` |
| 27 testów | 152 testów jednostkowych + 20 instrumentacyjnych |

---

## Co dalej

1. **Przejdź Etap 0 z [URUCHOMIENIE.md](URUCHOMIENIE.md)** — cała ścieżka
   aplikacji da się sprawdzić na symulatorze, jeszcze zanim okulary dotrą.
2. **Po dostawie: etapy 1–5 z tego samego dokumentu.** Najbardziej niepewne są
   bajty komend wideo/audio, mapa ramek notify i numer typu pliku dla nagrań —
   mogą się różnić między wersjami firmware. Ekran diagnostyczny pokazuje surowe
   ramki, więc rozbieżność będzie widać od razu.
3. Rozważ dodanie detekcji twarzy (ML Kit) i alertów IMGW, jeśli są istotne.
