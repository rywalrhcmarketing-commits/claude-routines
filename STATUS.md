# Status projektu Jarvis — raport z przejęcia

> Zastępuje ustalenia z `HANDOFF.md`. Kilka twierdzeń z tamtego dokumentu nie potwierdziło się
> w kodzie — szczegóły niżej.

## TL;DR

**Aplikacja się buduje.** APK debug powstaje na GitHub Actions, wszystkie testy
jednostkowe przechodzą. Wcześniej projekt nie przechodził nawet przez kompilator.

Pełny obraz tego, co działa, a czego nie — w [AUDIT.md](AUDIT.md).

### Jak zbudować

Build chodzi automatycznie przy każdym pushu na ten branch
(`.github/workflows/build.yml`). Gotowy APK jest do pobrania jako artefakt
`jarvis-debug-apk` z zakładki Actions.

Lokalnie na Macu:

```bash
cd jarvis-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
```

### Czego to nie oznacza

Zielony build znaczy tylko tyle, że kod się kompiluje, pakuje i przechodzi
testy jednostkowe. **Nic nie zostało sprawdzone na żywym sprzęcie** — ani na
emulatorze, ani na okularach. Bajty komend BLE, ramki notify i przepływ
Wi-Fi Direct są zgodne z oficjalnym przewodnikiem SDK producenta i z działającą
aplikacją referencyjną, ale wymagają potwierdzenia na Twoim egzemplarzu.

## Weryfikacja 8 bugów z HANDOFF.md

| # | Bug wg HANDOFF | Stan faktyczny |
|---|----------------|----------------|
| 1 | `HeyCyanManager` w `AIOrchestrator.kt:75` | ✅ było naprawione |
| 2 | `HeyCyanManager` w komentarzu | ⚠️ pozostałość w `BurstCaptureManager.kt:24` — naprawione teraz |
| 3 | `photos.first().bytes` | ✅ było naprawione |
| 4 | `putJsonObject` bez nawiasu | ✅ było naprawione |
| 5 | `JarvisManager.kt` używa API którego AAR nie ma | ❌ **NIE było naprawione** — przepisanie wprowadziło nowe, poważniejsze błędy |
| 6 | Calendar API `v3-rev99-1.2.0` | ✅ było naprawione |
| 7 | Brak `gradle.properties` | ✅ było naprawione |
| 8 | Brak `gradlew` | ❌ **NIE było naprawione** — `gradle/wrapper/` był pusty, `gradlew` to 18-liniowy placeholder |

### Dlaczego bug #5 nie był naprawiony

Poprzednia sesja przepisała `JarvisManager.kt` z 484 do 295 linii, żeby „używać tylko tego,
co AAR faktycznie ma". Problem w tym, że:

1. Nadal wywoływała `glassesControl()` z **jednym** argumentem — a prawdziwa sygnatura to
   `glassesControl(byte[], ILargeDataResponse<GlassModelControlResponse>)`. 6 błędów kompilacji.
2. Skrócenie pliku **usunęło API, z którego korzystały 3 inne pliki** — skanowanie, parowanie
   i zdarzenia przycisku. Kolejne 8 błędów.

---

## Znalezione błędy kompilacji (13)

| Plik | Problem |
|------|---------|
| `ble/JarvisManager.kt` | `glassesControl()` z 1 argumentem zamiast 2 — 6× |
| `ui/pairing/PairingViewModel.kt` | `ConnectionState.SCANNING` nie istniał |
| `ui/pairing/PairingViewModel.kt` | `ConnectionState.CONNECTED` nie istniał |
| `ui/pairing/PairingViewModel.kt` | typ `DiscoveredDevice` nie istniał |
| `ui/pairing/PairingViewModel.kt` | `startScan()`, `stopScan()`, `connect()`, `discoveredDevices` nie istniały |
| `ble/ButtonActionDetector.kt` | typ `ButtonEvent` **nie był zdefiniowany nigdzie w projekcie** |
| `AIOrchestrator.kt` | `buttonEvent`, `consumeButtonEvent()` nie istniały |
| `ui/pairing/PairingViewModel.kt` | `import pl.jarvis.app.HeiCyanApplication` — klasa nie istnieje |
| `ui/MainViewModel.kt` | to samo |
| `ui/settings/SettingsActivity.kt` | to samo, 2× w pełni kwalifikowane |
| `ui/settings/SettingsActivity.kt` | `JarvisApplication.audio` — właściwość nie istniała |

---

## Co zostało naprawione

### 1. `JarvisManager.kt` — przepisany na realne API (679 linii)

Wszystko zweryfikowane przez `javap` na `glasses_sdk_20250723_v01.aar` oraz porównane
z działającą aplikacją referencyjną
[CyanBridge](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK) — tą samą,
z której pochodzi AAR.

- **Stan połączenia** — z broadcastów `BleAction` (`BLE_GATT_CONNECTED`,
  `BLE_SERVICE_DISCOVERED`, `BLE_GATT_DISCONNECTED`). Wcześniej `initialize()` ustawiał
  na sztywno `READY` z komentarzem „TEST MODE", więc aplikacja twierdziła, że okulary są
  gotowe, nawet gdy nic nie było podłączone.
- **Skanowanie i parowanie** — `BleScannerHelper.scanDevice()` + `BleOperateManager.connectDirectly()`.
- **IP okularów** — faktycznie ustawiane z ramki notify `0x08`. Wcześniej pole `_glassesIp`
  **nigdy nie było zapisywane**, więc każde pobranie pliku kończyło się wyjątkiem.
- **Bateria** — realny odczyt z ramki `0x05`. Wcześniej: `Log.d("Battery level: 75% (placeholder)")`.
- **Przycisk na okularach** — z ramki `0x03`.
- **Singleton** — poprawione podwójne sprawdzanie z blokadą (wcześniej wyścig).

### 2. Zdjęcia idą teraz ścieżką, która działa

Pobieranie szło przez HTTP po Wi-Fi Direct — a projekt **nie ma ani jednej linii obsługi
Wi-Fi P2P** (`WifiP2pManager`, `bindProcessToNetwork` — zero trafień). Telefon nigdy nie
dołączał do sieci okularów, więc żądanie HTTP nie miało jak dojść.

Przełączyłem przechwytywanie na **miniatury po BLE** (`capturePhoto()`):
`glassesControl(0x02, 0x01, 0x06, q, q)` → `getPictureThumbnails()`. Nie wymaga Wi-Fi.
Zamiast sztywnego `delay(4000)` czekam na ramkę notify „zdjęcie gotowe" (`0x02`),
z odczekaniem jako zapasem — czyli tak szybko, jak pozwala sprzęt.

Zmienione: `BurstCaptureManager` (burst) i `AccessibilityService` (4 miejsca — tryby dla
osób niewidomych).

### 3. Gradle wrapper

Wygenerowany prawdziwy wrapper 8.7 (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`,
`gradle-wrapper.properties`).

---

## Protokół okularów — referencja

Ramki notify, `loadData[6]` = typ zdarzenia:

| Kod | Znaczenie | Dane |
|-----|-----------|------|
| `0x02` | Zdjęcie AI gotowe / przycisk foto | — |
| `0x03` | Przycisk AI / mikrofon | `[7] == 1` → wciśnięty |
| `0x04` | Postęp OTA | download / soc / nor |
| `0x05` | Bateria | `[7]` = %, `[8]` = 1 gdy ładowanie |
| `0x08` | IP okularów | `[7..10]` = IPv4 |
| `0x09` | Błąd P2P | `[7] == 0xFF` częsty, nie zawsze fatalny |
| `0x0c` | Pauza / komunikat głosowy | `[7] == 1` |
| `0x0e` | Mało pamięci na okularach | — |

Rejestracja: `LargeDataHandler.getInstance().addOutDeviceListener(100, listener)`,
gdzie listener dziedziczy po `GlassesDeviceNotifyListener`.

Komendy `glassesControl(payload, callback)`:

| Payload | Działanie |
|---------|-----------|
| `02 01 04` | Tryb transferu (Wi-Fi Direct) |
| `02 01 06 q q` | Zdjęcie AI + miniatura (`q` = jakość) |
| `02 01 0F` | Reset P2P |
| `02 04 01` / `02 05 01` | Start / stop wideo |
| `02 06 01` / `02 07 01` | Start / stop audio |

HTTP po Wi-Fi Direct (gdy już zostanie zaimplementowany):
`http://<ip>/files/media.config` (lista, jedna nazwa na linię), `http://<ip>/files/<nazwa>`.

---

## Co zostało do zrobienia

### 1. Pierwszy build — u Ciebie na Macu

```bash
cd jarvis-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug 2>&1 | tee build.log
```

Wrapper jest już prawdziwy, więc powinno zadziałać bez `gradle wrapper`.
Jeśli coś padnie — przyślij `build.log`.

Uwaga: kod pisałem pod **JDK 17** (tak ma ustawiony projekt). W kontenerze było JDK 21.

### 2. Wi-Fi Direct — potrzebny do wideo i pełnej rozdzielczości

Brakujący element. Do zrobienia (wzorzec: `WifiP2pManagerSingleton.kt` w CyanBridge):

- `WifiP2pManager` — discovery, połączenie przez WPS PBC
- `bindProcessToNetwork()` — bez tego na Samsungach ruch idzie złą trasą
- Uprawnienie `NEARBY_WIFI_DEVICES` (Android 13+) — **jeszcze nie dodane do manifestu**,
  bo funkcja nie istnieje
- Pułapka: `WifiP2pInfo.groupOwnerAddress` to zwykle **telefon** (`192.168.49.1`),
  nie okulary — używać IP z ramki `0x08`

Do czasu jego dodania działa: zdjęcia (BLE), sterowanie, bateria, przycisk.
Nie działa: pobieranie wideo i audio.

### 3. Testy z prawdziwym sprzętem

Po dostawie okularów — sparować i sprawdzić, czy kody ramek się zgadzają z tym firmware.
Logi: `adb logcat -s JarvisManager`.
