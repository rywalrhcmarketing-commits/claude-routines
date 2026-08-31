# Jarvis — asystent AI na okulary HeyCyan

Aplikacja na Androida, która robi z okularów HeyCyan asystenta: wciskasz przycisk
na oprawce, okulary robią zdjęcie, model opisuje co widzi, a odpowiedź wraca głosem.

## Od czego zacząć

| Chcę… | Zajrzyj do |
|---|---|
| **zobaczyć, czy to działa, zanim przyjdą okulary** | [URUCHOMIENIE.md](URUCHOMIENIE.md), Etap 0 |
| **uruchomić z prawdziwym sprzętem** | [URUCHOMIENIE.md](URUCHOMIENIE.md), etapy 1–5 |
| **wiedzieć, w jakim stanie jest projekt** | [STATUS.md](STATUS.md) |
| **wiedzieć, co konkretnie było zepsute i jak naprawione** | [AUDIT.md](AUDIT.md) |

`HANDOFF.md` to dokument z poprzedniej sesji, zachowany dla historii — kilku jego
twierdzeń nie potwierdziłem w kodzie, szczegóły w STATUS.md.

## Gotowy APK

Build chodzi automatycznie przy każdym pushu na branch roboczy. Najnowszy APK
pobierzesz z zakładki **Actions** → ostatni zielony przebieg → artefakt
`jarvis-debug-apk`.

```bash
adb install -r jarvis-debug.apk
```

## Build lokalnie

Wymaga JDK 17 i Android SDK.

```bash
cd jarvis-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
./gradlew assembleDebug
```

## Testy

```bash
cd jarvis-app
./gradlew testDebugUnitTest          # jednostkowe, bez urządzenia
./gradlew connectedDebugAndroidTest  # instrumentacyjne, wymaga emulatora
```

Testy instrumentacyjne przechodzą **całą ścieżkę komunikacji z okularami** na
symulatorze: skan, połączenie, komendy, dekodowanie ramek, miniatura sprawdzana
przez `BitmapFactory`, tryb transferu i pobieranie plików. Udawany jest wyłącznie
transport BLE — reszta to kod produkcyjny.

## Czego ten model okularów nie potrafi

Żeby nie tracić czasu na szukanie: nie ma wyświetlacza, nie udostępnia strumienia
z kamery ani z mikrofonu na żywo. Nagrania i zdjęcia odbiera się jako pliki, po
fakcie. Szczegóły i powody w sekcji „Znane granice" w [URUCHOMIENIE.md](URUCHOMIENIE.md).
