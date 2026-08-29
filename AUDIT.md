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

### Trzy funkcje, które wyglądały na działające

Wyszły z ostrzeżeń kompilatora - w każdym przypadku kod istniał, kompilował
się i nic nie robił.

**`QRScanner.scan()`** uruchamiał skan ML Kit, ignorował wynik i **zawsze**
zwracał pustą listę, z komentarzem „placeholder". `scanImageBytes()`
i `scanFile()` delegowały do niego. Realni użytkownicy wołają warianty
`*Sync`, więc błędu nie było widać - ale każdy nowy kod, który sięgnąłby
po `scan()`, po cichu nie znajdowałby żadnego kodu.

**Tryby przechwytywania** różniły się wyłącznie nazwą i liczbą klatek.
`ImageResolution` niósł `maxWidth`, `maxHeight` i `jpegQuality`, był
przekazywany przez cały łańcuch wywołań i nigdzie nie używany - `FAST_BURST`
wysyłał do modelu dokładnie to samo co `HIGH_QUALITY_SINGLE`. Nowy
`ImageScaler` przekłada rozdzielczość na jakość miniatury (0-6) i dopasowuje
wynik do limitów trybu, co przekłada się na koszt zapytania i czas odpowiedzi.

**Pytania tekstowe wymagały okularów.** `handleUserTrigger` przyjmował
`TriggerSource` z siedmiu miejsc wywołania i nigdzie go nie używał, przez co
aplikacja odmawiała odpowiedzi na cokolwiek bez połączonych okularów - także
na „ile to 20 euro w złotych". Warstwa AI była na to gotowa (providerzy budują
prompt zależnie od `images.isNotEmpty()`, a gałąź cache ma warunek
`photos.isEmpty()`), tylko nieosiągalna.

### Komendy głosowe — 11 z 16 reagowało na coś innego

Porcupine ma **14 wbudowanych komend i wszystkie są angielskie** (sprawdzone
`javap` na `porcupine-android-3.0.0.aar`). Aplikacja oferowała 16 fraz, w tym
polskie („Cześć", „Słuchaj", „Asystencie") i własną, a `toBuiltInKeyword()`
przy nieznanej nazwie po cichu zwracało `JARVIS`.

Użytkownik wybierał „Hej Jarvis", urządzenie nasłuchiwało „jarvis", i nie było
jak tego zauważyć poza jedną linią w logu. Dotyczyło to także **domyślnej**
pozycji „Jarvis Start".

Katalog mówi teraz prawdę: każdy wpis niesie nazwę wbudowanej komendy albo
`null`, gdy potrzebny jest wytrenowany model `.ppn`. Detektor odmawia
uruchomienia zamiast podmieniać frazę, a `initialize()` zwraca `InitResult`
z konkretną instrukcją zamiast gołego `false`.

Przy okazji własna fraza faktycznie działa — doszła obsługa `setKeywordPath`
(`.ppn`) i `setModelPath` (`.pv` dla języków innych niż angielski).

### Tryb konwersacyjny nigdy nie słyszał ani słowa

`ConversationalMode` czekał na tekst przez `deliverSpeech()`, ale ta metoda
**nie była wołana z żadnego miejsca w aplikacji**. `speechResult` zawsze
zostawał `null`, nasłuchiwanie wypadało na timeout i tryb sam się wyłączał.
W całym projekcie nie było żadnego `SpeechRecognizer`.

Nowy `SpeechToText` omija dwie pułapki tego API: instancję trzeba tworzyć
i wołać z wątku głównego, a po `onError`/`onResults` bywa nieużywalna. Mikrofon
jest wyłączny, więc wykrywanie komendy jest wstrzymywane na czas słuchania
i wznawiane w `finally`.

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
| 27 testów | 170 testów jednostkowych + 29 instrumentacyjnych |

---

## Co dalej

1. **Przejdź Etap 0 z [URUCHOMIENIE.md](URUCHOMIENIE.md)** — cała ścieżka
   aplikacji da się sprawdzić na symulatorze, jeszcze zanim okulary dotrą.
2. **Po dostawie: etapy 1–5 z tego samego dokumentu.** Najbardziej niepewne są
   bajty komend wideo/audio, mapa ramek notify i numer typu pliku dla nagrań —
   mogą się różnić między wersjami firmware. Ekran diagnostyczny pokazuje surowe
   ramki, więc rozbieżność będzie widać od razu.
3. Rozważ dodanie detekcji twarzy (ML Kit) i alertów IMGW, jeśli są istotne.
