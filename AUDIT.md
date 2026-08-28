# Audyt listy funkcji — co realnie działa

> Weryfikacja listy „~95+ ficzerów" względem kodu. Metoda: analiza statyczna źródeł
> (sprawdzenie, czy klasa istnieje, czy jest gdziekolwiek tworzona, i czy ustawienie
> jest odczytywane przez logikę, a nie tylko przez ekran ustawień).
>
> **Żadna funkcja nie została sprawdzona w działaniu** — projekt nie był jeszcze skompilowany.

## Liczby na wstępie

| Deklarowane | Faktyczne |
|---|---|
| 98 plików | 73 pliki `.kt` (118 wszystkich) |
| 17 317 linii | 16 735 linii `.kt` |
| 27 testów jednostkowych | 30 metod `@Test` — jedyna liczba zaniżona |

---

## ❌ Nie zadziała — kod istnieje, ale nie jest podłączony

**Tłumacz symultaniczny (cała sekcja, 5 pozycji).**
`SimultaneousTranslator` **nigdy nie jest tworzony**. Z całej klasy używane są wyłącznie
statyczne pomocniki (`languageName`, `SUPPORTED_LANGUAGES`) — do narysowania listy języków
w ustawieniach. Można wybrać język docelowy i nic się nie przetłumaczy.
17 języków offline jest zadeklarowanych w kodzie, ale nic ich nie używa.

**Pamięć długoterminowa (cała sekcja, 4 pozycje).**
`LongTermMemory` **nigdy nie jest tworzony**. Istnieje przełącznik
`isLongTermMemoryEnabled()` w ustawieniach — zapisuje się, nic nie robi.
TF-IDF jest zaimplementowany, ale nikt go nie wywołuje.

## ❌ Nie zadziała — brak implementacji

**Pogoda — 5 z 10 pozycji.** `WeatherService` ma tylko prognozę OpenWeatherMap
(`/forecast`), geokodowanie i wykrywanie deszczu. Brak w kodzie:
jakości powietrza PM2.5/PM10, alertów IMGW, wschodu/zachodu słońca, indeksu UV,
alertów meteorologicznych OWM.

**Accessibility — funkcje „wykrywania".** Nie ma żadnej wizji komputerowej.
„Wykrywa twarz / przeszkodę / auto" to **wyłącznie tekst wysyłany w promcie do AI**
(`AIOrchestrator.kt:117`, `PersonaRegistry.kt:180`). Nie ma detekcji twarzy,
pomiaru odległości ani rozpoznawania obiektów.

> ⚠️ **Uwaga bezpieczeństwa.** Deklaracje typu „STOP — ściana 50 cm przed Tobą" i
> „Auto jedzie z lewej, STOP" sugerują funkcję bezpieczeństwa dla osób niewidomych.
> Model językowy nie zmierzy odległości z jednej miniatury i nie ma gwarancji czasu
> odpowiedzi. Tego nie wolno przedstawiać jako asysty przy poruszaniu się.

**Pamięć twarzy** („To Anna, była tu 2 dni temu") — brak jakiegokolwiek kodu.

**Pozostałe brakujące:** vCard z QR, blokowanie screenshotów (`FLAG_SECURE`),
auto-blokada po 5 min, wysoki kontrast, duże litery, redakcja kluczy w logach
(`[REDACTED]`), crash log do pliku, bottom navigation.

## ⚠️ Zadziała inaczej niż opisano

**Wideo (VIDEO_SHORT, VIDEO_LONG).** Nagrywanie wystartuje, ale pliku nie da się pobrać —
brak Wi-Fi Direct. Nagranie zostaje na okularach.

**HIGH_QUALITY_SINGLE „HD 2560x1440".** Zdjęcia idą teraz miniaturami po BLE
(jedyna działająca ścieżka). To nie jest 2560x1440. Parametr `ImageResolution`
jest przekazywany przez `BurstCaptureManager`, ale **nigdy nie użyty** — tylko logowany.

**Konfigurowalny interwał alertów (15/30/60 min).** `getProactiveIntervalMinutes()`
nie jest nigdzie czytany. Interwał jest zaszyty na sztywno.

**Ustawienia głosu TTS.** `getTtsVoiceName()`, `getTtsSpeechRate()`, `getTtsPitch()`
są czytane wyłącznie w ekranie ustawień. Zmiana działa jako podgląd na żywo,
ale nie jest przywracana przy starcie aplikacji.

**Limit historii.** `getHistoryLimit()` nie jest nigdzie czytany — „20 ostatnich rozmów"
nie jest egzekwowane tym ustawieniem.

**Auto-degradacja trybu capture.** `isAutoDegradeCaptureEnabled()` czytane tylko w ustawieniach.

**Custom wake word.** `getCustomWakeWord()` czytane tylko w ustawieniach. Dodatkowo
Porcupine nie ma polskich słów kluczowych — `JarvisApplication.resolvePicovoiceKeyword()`
mapuje wszystkie polskie warianty na angielskie „jarvis".

**AAR „odporny na zmiany protokołu".** Nieprawda — bajty komend (`02 01 06` itd.)
są zaszyte w warstwie aplikacji, nie w SDK. Zmiana protokołu je zepsuje.
AAR jest z 07/2025 (`glasses_sdk_20250723`), nie z 07/2023.

## ✅ Powinno działać (po udanym buildzie)

Sprawdzone jako obecne w kodzie i podłączone:

- **AI multimodalne** — 4 providery, fallback, streaming odpowiedzi, wybór modelu
- **Model discovery i migracja deprecated** — `ModelDiscoveryService`, `SmartModelResolver`
- **TTS** — audio focus (ściszanie muzyki), streaming zdanie po zdaniu, głosy offline/online
- **Wake word** — Porcupine on-device (z zastrzeżeniem o języku wyżej)
- **OCR** — ML Kit, czytanie tekstu z obrazu
- **QR / kody kreskowe** — ML Kit, w tym EAN
- **Web fetcher** — pobieranie i analiza URL z QR
- **Akcje głosowe** — 20 typów w `Action.kt` (więcej niż deklarowane 16)
- **Google Calendar** — OAuth2, odczyt eventów, tworzenie eventów
- **Pogoda** — prognoza, deszcz, wiatr, alerty proaktywne co 15 min (WorkManager)
- **Multi-turn context** — `ConversationalMode`, 9 odwołań
- **Historia** — Room DB, wyszukiwanie, udostępnianie, cleanup po 30 dniach
- **Persony** — 11 zdefiniowanych
- **Szyfrowanie kluczy** — EncryptedSharedPreferences
- **Motyw** — Material 3 z dynamic color
- **Onboarding** — 9 kroków

## Podsumowanie liczbowe ustawień

Z 34 getterów w `SettingsRepository`: **22 realnie używane** przez logikę,
9 czytanych tylko w ekranie ustawień, 3 nieczytane w ogóle.

---

## Co zrobić najpierw

1. **Zbuduj projekt** — nic z powyższego nie jest potwierdzone w działaniu.
2. **Podłącz tłumacz i pamięć długoterminową** — kod jest napisany, brakuje kilku linii
   w `AIOrchestrator`, żeby zaczął być używany. Najtańszy zysk.
3. **Podłącz ustawienia** — 12 przełączników, które dziś nic nie robią.
4. **Wi-Fi Direct** — odblokuje wideo i pełną rozdzielczość.
5. **Skoryguj opis accessibility** — funkcje „wykrywania" to prompt do AI, nie detekcja.
