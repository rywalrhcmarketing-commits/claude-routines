# User Stories – HeyCyan AI Glasses MVP

> Wersja: 0.1.0-alpha  
> Data: 2026-08-28  
> Zakres: Minimum Viable Product (pierwsze wydanie dla siebie)

## Kontekst

Aplikacja na Androida, która łączy tanie okulary HeyCyan (kamera 8MP + mikrofon + głośniki + BLE) z zewnętrznym AI (Gemini domyślnie, z opcją OpenAI/Claude/MiniMax). Główny flow: użytkownik aktywuje przycisk, apka przechwytuje obraz i dźwięk, wysyła do AI, odtwarza odpowiedź głosem przez głośniki okularów.

---

## EPIC 1: Połączenie z okularami i konfiguracja

### US-001: Sparowanie z HeyCyan przez Bluetooth

**Jako** użytkownik  
**Chcę** sparować okulary HeyCyan z aplikacją przez Bluetooth  
**Żeby** móc korzystać z ich kamery, mikrofonu i głośników

**Kryteria akceptacji:**
- [ ] Apka wykrywa urządzenia HeyCyan w pobliżu (skan BLE)
- [ ] Apka filtruje urządzenia po UUID `7905FFF0` lub `6e40fff0`
- [ ] Proces parowania trwa < 30 sekund
- [ ] Po udanym sparowaniu wyświetla się potwierdzenie z nazwą urządzenia
- [ ] Sparowane urządzenie zapisuje się w `EncryptedSharedPreferences`
- [ ] Przy kolejnym uruchomieniu apka automatycznie łączy się z zapisanym urządzeniem

**Priorytet:** Must Have (P0)

---

### US-002: Weryfikacja autentyczności okularów

**Jako** użytkownik  
**Chcę** zweryfikować że moje okulary to prawdziwy HeyCyan  
**Żeby** uniknąć problemów z klonymi które mają inny protokół

**Kryteria akceptacji:**
- [ ] Po sparowaniu apka wyświetla diagnostic info (Service UUID, manufacturer, model)
- [ ] Apka waliduje czy Service UUID zawiera `7905FFF0` lub `6e40fff0`
- [ ] Jeśli walidacja nie przejdzie – wyświetl ostrzeżenie z opcją zwrotu
- [ ] Dostępna instrukcja jak zweryfikować UUID przez nRF Connect (dla pewności)

**Priorytet:** Must Have (P0)

---

### US-003: Konfiguracja klucza API

**Jako** użytkownik  
**Chcę** wpisać swój klucz API Gemini (lub innego providera) w ustawieniach  
**Żeby** móc korzystać z AI

**Kryteria akceptacji:**
- [ ] Ekran ustawień z polem tekstowym na klucz API
- [ ] Klucz zapisywany w `EncryptedSharedPreferences` (MasterKey z AES256_GCM)
- [ ] Klucz NIGDY nie jest logowany
- [ ] Przy pierwszym uruchomieniu wyświetlana instrukcja jak pobrać klucz (link do Google AI Studio)
- [ ] Możliwość wyboru provider-a (Gemini / OpenAI / Claude / MiniMax) z osobnymi polami na klucze
- [ ] Test połączenia z AI (wysyła "hello" i czeka na odpowiedź)

**Priorytet:** Must Have (P0)

---

## EPIC 2: Główny flow AI

### US-004: Aktywacja przez przycisk fizyczny okularów

**Jako** użytkownik  
**Chcę** wcisnąć przycisk na zauszniku okularów żeby aktywować AI  
**Żeby** szybko zadać pytanie bez wyciągania telefonu

**Kryteria akceptacji:**
- [ ] Apka nasłuchuje eventu "button press" przez BLE
- [ ] Po wciśnięciu: emit "bip" przez głośniki (potwierdzenie dźwiękowe)
- [ ] Rozpoczyna się nagrywanie audio + robienie pierwszego zdjęcia
- [ ] Po puszczeniu przycisku: zatrzymuje nagrywanie
- [ ] Triggers analiza AI (patrz US-005)

**Priorytet:** Must Have (P0)

---

### US-005: Przechwycenie 5 zdjęć w 10 sekund

**Jako** użytkownik  
**Chcę** żeby apka automatycznie zrobiła 5 zdjęć w 10 sekund  
**Żeby** AI miało pełniejszy kontekst sytuacji

**Kryteria akceptacji:**
- [ ] Po aktywacji: zdjęcie 1 natychmiast (t=0)
- [ ] Kolejne zdjęcia co 2 sekundy (t=2, 4, 6, 8)
- [ ] Maksymalnie 5 zdjęć
- [ ] Każde zdjęcie zapisywane w pamięci jako `ByteArray`
- [ ] Jeśli shutter BLE nie odpowie w 3s, przejdź do następnego
- [ ] Wizualny wskaźnik na telefonie (kółko postępu: ● ● ● ● ●)

**Priorytet:** Must Have (P0)

---

### US-006: Wysłanie do AI i odpowiedź głosowa

**Jako** użytkownik  
**Chcę** usłyszeć odpowiedź AI w głośnikach okularów  
**Żeby** szybko dostać informację bez patrzenia na telefon

**Kryteria akceptacji:**
- [ ] Apka wysyła do AI: 5 zdjęć + transkrypcję pytania audio
- [ ] Apka wstępnie wskazuje: "AI myśli..." (bip lub animacja)
- [ ] Otrzymana odpowiedź tekstowa jest syntezowana do audio (Android TTS lub zewnętrzny TTS)
- [ ] Audio odtwarzane przez głośniki okularów
- [ ] Domyślny język odpowiedzi: polski
- [ ] Max czas oczekiwania: 15s (timeout)
- [ ] Błąd sieci: powtórzenie z komunikatem "Spróbuj ponownie"

**Priorytet:** Must Have (P0)

---

## EPIC 3: Historia i ustawienia

### US-007: Historia ostatnich 20 rozmów

**Jako** użytkownik  
**Chcę** przeglądać historię moich ostatnich pytań i odpowiedzi  
**Żeby** wrócić do wcześniejszych informacji

**Kryteria akceptacji:**
- [ ] Lista ostatnich 20 wpisów (najnowsze na górze)
- [ ] Każdy wpis: miniaturka pierwszego zdjęcia + pytanie (skrócone) + odpowiedź (skrócona)
- [ ] Klik = pełny widok (duże zdjęcie + cała odpowiedź)
- [ ] Możliwość usunięcia pojedynczego wpisu
- [ ] Możliwość wyczyszczenia całej historii (z potwierdzeniem)
- [ ] Dane przechowywane lokalnie w Room database

**Priorytet:** Should Have (P1)

---

### US-008: Text mode (alternatywa dla głosu)

**Jako** użytkownik  
**Chcę** wpisać pytanie tekstowo zamiast mówić  
**Żeby** móc korzystać z apki w cichym otoczeniu (biuro, biblioteka)

**Kryteria akceptacji:**
- [ ] Pole tekstowe w głównym ekranie
- [ ] Po wciśnięciu "Wyślij": standardowy flow (5 zdjęć + tekst → AI → odpowiedź głosowa)
- [ ] Historia zapisuje zarówno pytania głosowe jak i tekstowe
- [ ] Domyślnie: focus na przycisku foto (text mode ukryty za ikonką klawiatury)

**Priorytet:** Should Have (P1)

---

### US-009: Wskaźnik "AI myśli"

**Jako** użytkownik  
**Chcę** widzieć kiedy AI przetwarza moje pytanie  
**Żeby** wiedzieć że apka nie zawiesiła się

**Kryteria akceptacji:**
- [ ] Po wysłaniu do AI: wyświetlany animowany wskaźnik (spinner / pulsujące kropki)
- [ ] Tekst "AI myśli..." lub "Przetwarzam..."
- [ ] Szacowany czas pozostały (opcjonalnie, jeśli API zwraca)
- [ ] Po otrzymaniu odpowiedzi: wskaźnik znika

**Priorytet:** Must Have (P0)

---

## EPIC 4: Integracje (v1.1)

### US-010: Web search (Gemini grounding)

**Jako** użytkownik  
**Chcę** żeby AI mogło szukać aktualnych informacji w internecie  
**Żeby** odpowiedzi były aktualne (pogoda, wiadomości, ceny)

**Kryteria akceptacji:**
- [ ] Dla Gemini: włączony Google Search grounding w zapytaniu
- [ ] AI widzi aktualne dane (np. pogodę, kursy walut, wiadomości)
- [ ] Odpowiedzi zawierają cytaty źródeł (jeśli są dostępne)
- [ ] Konfigurowalne: włącz/wyłącz web search w ustawieniach

**Priorytet:** Should Have (P1) - **ale tanie dodać**

---

### US-011: QR code scanning

**Jako** użytkownik  
**Chcę** żeby apka automatycznie rozpoznawała QR kody w przechwyconych zdjęciach  
**Żeby** móc szybko zeskanować menu w restauracji, link, wizytówkę

**Kryteria akceptacji:**
- [ ] Używa ML Kit Barcode Scanner (offline, free)
- [ ] Jeśli QR wykryty: automatycznie dołącza treść QR do zapytania do AI
- [ ] AI interpretuje treść QR (URL → opis strony, vCard → zapisz kontakt, WiFi → połącz)
- [ ] Wizualne potwierdzenie "Wykryto QR kod"

**Priorytet:** Could Have (P2) - **ale proste dodać**

---

## Poza scope MVP (v2+)

- Wake word "Hej Cyan" (opcja w ustawieniach)
- Calendar integracja (Google Calendar)
- Notes (Google Keep, Notion)
- Email (draft only, z potwierdzeniem)
- Własne persony AI
- Multi-language odpowiedzi
- Sharing odpowiedzi do Messengera

---

## Definition of Done dla MVP

- [ ] Wszystkie P0 (Must Have) zaimplementowane
- [ ] Przetestowane z prawdziwymi okularami HeyCyan
- [ ] Brak crashy przez 1h ciągłego używania
- [ ] Polskie komunikaty błędów
- [ ] Działa offline (apka startuje) – AI wymaga internetu
- [ ] APK zainstalowany na A20 i gotowy do użycia
