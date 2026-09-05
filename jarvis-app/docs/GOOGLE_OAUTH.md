# Konto Google: co ustawić w Google Cloud Console

Kalendarz i Gmail w V.I.C.T.O.R.-ze wymagają konfiguracji **po stronie Google**.
Samego kodu nie da się tak napisać, żeby to obszedł - Google wiąże aplikację z
projektem OAuth, a nie z plikiem APK.

## Dlaczego to wcześniej nie działało

Komunikat "klient OAuth nie jest skonfigurowany" bierze się stąd, że Google
rozpoznaje aplikację po **dwóch rzeczach naraz**:

1. nazwie pakietu,
2. odcisku SHA-1 klucza, którym APK jest podpisany.

Debugowe APK były wcześniej podpisywane kluczem generowanym na maszynie CI przy
każdym buildzie. Odcisk SHA-1 zmieniał się więc co build - a wpisu w Google
Cloud Console nie da się utrzymać przy ruchomym celu. Dlatego klucz jest teraz
STAŁY i leży w repozytorium (`jarvis-app/keystore/debug.keystore`).

To jest klucz **wyłącznie debugowy**, z hasłem `android` - takim samym, jakie ma
domyślny klucz debugowy Androida na każdym komputerze świata. Nie chroni niczego
i nie wolno nim podpisywać wydania.

## Wartości do wklejenia

| Pole | Wartość |
|------|---------|
| Typ klienta OAuth | **Android** |
| Nazwa pakietu | `pl.victor.app.debug` |
| Odcisk SHA-1 | `39:01:DB:1F:51:31:88:26:0D:9B:A6:1A:E7:42:79:1B:3B:04:FC:F0` |

Nazwa pakietu ma na końcu `.debug` (`applicationIdSuffix` w `build.gradle.kts`).
Wpisanie samego `pl.victor.app` nie zadziała.

Odcisk można w każdej chwili sprawdzić samodzielnie:

```
keytool -list -v -keystore jarvis-app/keystore/debug.keystore \
        -storepass android -alias androiddebugkey
```

## Kroki

1. **Google Cloud Console → API i usługi → Biblioteka**: włącz **Google Calendar
   API** i **Gmail API**.
2. **Ekran zgody OAuth**: typ zewnętrzny. Dodaj cztery zakresy:
   - `https://www.googleapis.com/auth/calendar`
   - `https://www.googleapis.com/auth/calendar.events`
   - `https://www.googleapis.com/auth/gmail.readonly`
   - `https://www.googleapis.com/auth/gmail.send`
3. **Ekran zgody → Użytkownicy testowi**: dodaj swój adres Gmail. Dopóki
   aplikacja jest w trybie testowym, każde inne konto dostanie odmowę - a zakresy
   Gmaila są przez Google traktowane jako wrażliwe, więc trybu testowego nie da
   się pominąć bez weryfikacji.
4. **Dane logowania → Utwórz dane logowania → Identyfikator klienta OAuth**: typ
   **Android**, nazwa pakietu i SHA-1 z tabeli wyżej.
5. Zainstaluj APK i zaloguj się w Ustawieniach.

## Czego NIE trzeba wklejać do aplikacji

Klient OAuth typu Android **nie ma sekretu klienta** i nie wymaga wpisywania
niczego w kod ani w ustawienia aplikacji. Całe powiązanie to para
(nazwa pakietu, SHA-1) sprawdzana przez Usługi Google Play na telefonie. Jeśli
szukasz pola na "kod klienta" w aplikacji - takiego pola nie ma i nie powinno
być.

## Gdy dalej nie działa

- **Błąd 10 (`DEVELOPER_ERROR`)** - para pakiet + SHA-1 się nie zgadza. Najczęstsza
  przyczyna: pominięte `.debug` w nazwie pakietu.
- **Błąd 403 przy pierwszym zapytaniu do Gmaila** - konto zalogowało się, zanim
  zakresy Gmaila trafiły do ekranu zgody. Wyloguj się w Ustawieniach i zaloguj
  ponownie; aplikacja sama traktuje konto bez kompletu zakresów jak
  niezalogowane, ale zgodę trzeba wydać jeszcze raz.
- **"Aplikacja niezweryfikowana"** - normalne w trybie testowym. Przejdź przez
  "Zaawansowane → Przejdź do V.I.C.T.O.R.".

## Wydanie

Wersja wydania jest podpisana innym kluczem, więc będzie potrzebowała **drugiego**
identyfikatora klienta OAuth: pakiet `pl.victor.app` (bez `.debug`) i SHA-1 klucza
wydania. Do jednego projektu można dodać wiele klientów Android - debugowego nie
trzeba wtedy kasować.
