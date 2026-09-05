# HeyCyan AI Glasses – Android App

Aplikacja na Androida łącząca okulary HeyCyan (kamera + mikrofon + głośniki) z wieloma modelami AI (Gemini, OpenAI, Claude, MiniMax).

## Co robi

Mówisz coś do okularów / wciskasz przycisk → apka robi 5 zdjęć co 2 sekundy → wysyła do AI z pytaniem → AI odpowiada głosem przez głośniki okularów.

Wszystko w języku polskim.

## Status

- [x] Research rynku i decyzja o stacku technologicznym
- [x] Wybór bazy kodu (FerSaiyan Alternative-HeyCyan-App-and-SDK → vendor AAR)
- [x] Architektura i user stories (patrz `docs/`)
- [x] Implementacja MVP – 39 plików, 5429 linii
  - [x] Vendor SDK (BleOperateManager, LargeDataHandler) przez AAR
  - [x] BurstCaptureManager (5 zdjęć co 2s)
  - [x] AI: Gemini 2.5 Flash + OpenAI GPT-4o + Claude Sonnet 4 + MiniMax
  - [x] Web search grounding (Gemini)
  - [x] AudioManager (TTS + nagrywanie)
  - [x] Room database (historia 20 ostatnich)
  - [x] UI: Main + Settings + History + Pairing (Compose)
  - [x] EncryptedSharedPreferences (klucze API AES-256)
  - [x] QR scanning (ML Kit Barcode) - na każdym zdjęciu
  - [x] Akcje przycisku: 1x=pytanie głosem, 2x=zdjęcie i opis, 3x=QR, przytrzymanie=reset
  - [x] ProGuard rules dla release
- [ ] Testy z prawdziwymi okularami (po dostawie 14-21 dni)

## Struktura projektu

```
heycyan-app/
├── app/
│   └── src/main/java/pl/heycyan/app/
│       ├── ai/          # Interfejs AIProvider + implementacje (Gemini, OpenAI, ...)
│       ├── ble/         # Komunikacja z okularami HeyCyan przez Bluetooth
│       ├── camera/      # Obsługa kamery okularów (pobieranie JPEG-ów)
│       ├── audio/       # Nagrywanie mikrofonu + odtwarzanie przez głośniki
│       ├── ui/          # Aktywności, fragmenty, viewmodele
│       ├── data/        # Repozytoria, baza danych (Room), preferencje
│       └── utils/       # Helpery (kryptografia, formatowanie)
├── docs/
│   ├── USER_STORIES.md  # History użytkownika z acceptance criteria
│   └── ARCHITECTURE.md  # Architektura techniczna
└── README.md            # Ten plik
```

## Jak zacząć

1. **Zainstaluj Android Studio** (koala lub nowszy)
2. **Sklonuj FerSaiyan/Alternative-HeyCyan-App-and-SDK** (branch `gemini-live`) do osobnego katalogu
3. **Skopiuj ich kod BLE/protokołu** do modułu `ble/` w tym projekcie
4. **Zaimplementuj interfejs `AIProvider`** (zacznij od `GeminiProvider`)
5. **Podepnij klucz API** w ustawieniach (ręcznie, nie przez kod)
6. **Zbuduj i testuj** na A20

## Konwencje

- **Kotlin** jako język główny
- **Jedna odpowiedzialność na klasę** – SRP
- **Brak hardcoded kluczy API** – user wkleja ręcznie w ustawieniach
- **EncryptedSharedPreferences** dla kluczy
- **Polskie komentarze** dla czytelności

## Wymagania sprzętowe

- Android 8+ (API 26+)
- Bluetooth 5.0+ (HeyCyan)
- Kamera (HeyCyan)
- Mikrofon (HeyCyan / telefon)
- Internet (komunikacja z API AI)

---

## 📚 Dokumentacja

- **[AGENTS.md](AGENTS.md)** - kompletna dokumentacja dla AI agentów i developerów przejmujących projekt
- **[QUICKSTART.md](QUICKSTART.md)** - szybki start (5 min)
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** - architektura
- **[docs/USER_STORIES.md](docs/USER_STORIES.md)** - historyjki użytkownika

## 🆘 Pomoc

Problemy? Sprawdź:
1. [AGENTS.md § 11 Hot fix'y](AGENTS.md#11-dalsze-kroki-dla-innego-ai)
2. FAQ w [AGENTS.md § 9](AGENTS.md#9-faq-dla-innego-ai)
