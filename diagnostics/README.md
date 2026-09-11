# Dzienniki diagnostyczne V.I.C.T.O.R.

Tu lądują logi wysyłane przez aplikację z telefonu — po jednym pliku na sesję,
nazwa `victor-<data>T<godzina>.log`.

**Ta gałąź celowo nie buduje aplikacji.** Workflow `build.yml` słucha wyłącznie
gałęzi `claude/jarvis-ai-glasses-project-zxsabo`, więc wysłanie dziennika nie
uruchamia piętnastominutowego buildu.

## Jak czytać

Każdy wiersz ma stałe kolumny:

```
HH:MM:SS.mmm   +1234 ms  ETAP         treść   pole=wartość pole=wartość
```

Druga kolumna to **czas od początku tury** — przy zgłoszeniu „długo trwa od
pytania do odpowiedzi" to jedyna liczba, która ma znaczenie. Turę otwiera wiersz
`--- TURA xxxx ---`, zamyka `--- KONIEC TURY xxxx ---`.

Etapy: `WAKE`, `PRZYCISK`, `NASŁUCH`, `TRANSKRYPCJA`, `ZDJĘCIE`, `KONTEKST`,
`MODEL`, `MOWA`, `AKCJA`, `BLE`, `AUDIO`, `BŁĄD`.

## Czego tu nie ma

Kluczy API ani tokenów. Każdy wiersz przechodzi przez zaciemnianie
(`DiagFormat.redact`) zanim trafi do pliku — zostaje sześć pierwszych znaków,
żeby dało się poznać, o który klucz chodzi, reszta znika.
