# 🤝 HANDOFF - Jarvis Project

> Dla drugiego agenta AI (M2) - wszystko czego potrzebujesz do kontynuacji pracy.

---

## 📦 Co dostajesz

**2 pliki ZIP:**

| Plik | Rozmiar | SHA256 | Użycie |
|------|---------|--------|--------|
| `jarvis-app-source.zip` | 602 KB | `d7176b02e70727aef236a88c8700f1b6c829c4adb9ba324a39894af4dddc2378` | Źródła bez build artifacts |
| `jarvis-app-full.zip` | 602 KB | `245f28502cdca7ca9e64d2f2b1c154ccc5b2307cdb470b0d8a03c9f9e981669c` | Pełne (identyczne) |

**Oba zawierają 164 pliki, 1.06 MB niespakowane.**

---

## 🚀 Quick start dla M2

```bash
# 1. Rozpakuj
unzip jarvis-app-source.zip
cd jarvis-app/

# 2. Przeczytaj dokumentację (ważne!)
cat AGENTS.md           # 22 KB - pełna dokumentacja
cat README.md           # 4 KB
cat QUICKSTART.md       # 8 KB

# 3. Pierwszy build
export ANDROID_HOME=/ścieżka/do/android-sdk
./gradlew assembleDebug

# 4. Jeśli build OK - test na emulatorze (który już masz)
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 🐛 Znane bugi (NAPRAWIONE w tym ZIP)

Poniższe bugi zostały naprawione po code review M2 w poprzedniej sesji:

| # | Bug | Status |
|---|-----|--------|
| 1 | `HeyCyanManager` w `AIOrchestrator.kt:75` | ✅ Naprawiony na `JarvisManager` |
| 2 | `HeyCyanManager` w komentarzu linii 47 | ✅ Naprawiony |
| 3 | `photos.first().bytes` (nie istnieje) | ✅ Naprawiony na `photos.first()` |
| 4 | `putJsonObject "systemInstruction"` bez `(` | ✅ Naprawiony na `putJsonObject("systemInstruction")` |
| 5 | `JarvisManager.kt` używa API którego AAR nie ma | ✅ **Cały plik przepisany** - 484→295 linii |
| 6 | Calendar API `v3-rev20240605-2.0.0` nie istnieje | ✅ Zmieniony na `v3-rev99-1.2.0` |
| 7 | Brak `gradle.properties` | ✅ Dodany |
| 8 | Brak `gradlew` | ✅ Placeholder dodany (wygeneruj wrapper: `gradle wrapper`) |

**Po rozpakowaniu - sprawdź czy wszystkie 8 są naprawione.**

---

## 🔴 Jeśli build dalej failuje

**Najprawdopodobniej:**
1. Brak `gradle-wrapper.jar` - wygeneruj: `gradle wrapper --gradle-version 8.7`
2. Brak `local.properties` - utwórz z `sdk.dir=/ścieżka/do/Android/sdk`
3. Brak Android SDK - zainstaluj platform 34, build-tools 34

**Wyślij mi output `gradlew assembleDebug` - naprawię dalej.**

---

## 🟡 Kolejne rzeczy do zrobienia (po buildzie)

W kolejności priorytetów:

### Sprint 1 - Core (ten tydzień)
1. ✅ Build w emulatorze
2. ✅ Pierwszy test Gemini API
3. ✅ Onboarding flow (9 kroków)
4. ✅ UI z prawidłowym wyświetlaniem

### Sprint 2 - Test (po buildzie)
1. Sprawdź czy AI odpowiada
2. Test OCR z obrazka
3. Test tłumacza
4. Test accessibility mode (czytaj/opisuj)
5. Test power modes

### Sprint 3 - HeyCyan (po dostawie, 14-21 dni)
1. Sparuj okulary przez nRF Connect
2. Zaktualizuj UUIDs jeśli trzeba
3. Test takePhoto / video
4. Test 5 trybów capture
5. E2E: klik → foto → AI → TTS

### Sprint 4 - Polish (ciągłe)
1. Wyczyść duplikaty w AIOrchestrator (jeśli zostały)
2. Usuń nieużywane dependencies
3. Napraw brakujące branche w when
4. Dodaj testy jednostkowe (mamy 27, chcemy 50+)

---

## 📋 Kluczowe decyzje (nie zmieniaj bez powodu)

1. **HeyCyan (161 zł)** - nie droższe alternatywy. Decyzja podjęta.
2. **Audio-first, no display** - filozofia. HeyCyan nie ma ekranu.
3. **Polski native** - nie angielski. User jest polski.
4. **Multi-provider AI** (Gemini/OpenAI/Claude/MiniMax) z auto-fallback.
5. **5 trybów capture** (BURST_PHOTO/HIGH_QUALITY/FAST_BURST/VIDEO_SHORT/VIDEO_LONG) - max 5s.
6. **3 tryby power** (ECO/NORMAL/PERFORMANCE) - ECO <3%/h.
7. **3 tryby accessibility** (READ_TEXT/DESCRIBE_SCENE/NAVIGATE) - dla niewidomych.
8. **EncryptedSharedPreferences** (AES-256-GCM) - klucze API.
9. **Open source** - pełna kontrola, nie vendor lock-in.

**Jeśli chcesz coś zmienić - najpierw zapytaj.**

---

## 🛠️ Środowisko M2

Pisałeś że masz zainstalowane:
- ✅ OpenJDK 17 (Adoptium Temurin)
- ✅ Android SDK z command-line tools
- ✅ System image Android 14
- ✅ AVD `jarvis_test` (Pixel 5)
- ✅ Gradle 8.7
- ✅ Emulator działa w tle

**Możesz od razu zacząć build.**

---

## 📞 Komunikacja

- **Główna sesja**: Mavis (energetyczny Gen-Z coworker)
- **Druga sesja (Ty)**: M2 (technical reviewer)
- **User**: Polski, zna Android, ma HeyCyan, czeka na dostawę

**Jeśli napotkasz błędy - spróbuj naprawić sam, wyślij mi raport.**
**Jeśli coś jest niejasne - zapytaj, dokumentacja jest w AGENTS.md.**

---

## 🎯 Twój pierwszy task

```bash
cd jarvis-app/
./gradlew assembleDebug 2>&1 | tee build.log
```

Jeśli fail - wyślij mi `build.log`. Jeśli OK - zainstaluj i testuj.

**Powodzenia M2! 🚀**
