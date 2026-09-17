#!/usr/bin/env python3
"""Przejście przez interfejs prawdziwą przeglądarką, na danych pokazowych.

Testy jednostkowe sprawdzają, co serwer odpowiada. To sprawdza, czy strona
faktycznie się rysuje, klika i nie sypie błędami w konsoli - czyli rzeczy,
których żaden test HTTP nie zobaczy.

    pip install playwright && playwright install chromium
    python tools/przejdz-ui.py            # cicho, kod wyjścia mówi wynik
    python tools/przejdz-ui.py zrzut.png  # dodatkowo zapisz zrzut ekranu
"""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

KORZEN = Path(__file__).resolve().parents[1]


def wolny_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def poczekaj(url: str, sekundy: float = 20) -> None:
    koniec = time.monotonic() + sekundy
    while time.monotonic() < koniec:
        try:
            urllib.request.urlopen(url, timeout=1)
            return
        except Exception:
            time.sleep(0.2)
    raise RuntimeError(f"Serwer nie wstał na {url}")


def przejdz(baza: str, zrzut: str | None) -> list[str]:
    from playwright.sync_api import sync_playwright

    bledy: list[str] = []
    przegladarka = os.environ.get("CHROMIUM_PATH")

    with sync_playwright() as pw:
        opcje = {"args": ["--no-sandbox"]}
        if przegladarka:
            opcje["executable_path"] = przegladarka
        browser = pw.chromium.launch(**opcje)
        page = browser.new_page(viewport={"width": 1000, "height": 1400})
        page.on("console", lambda m: bledy.append(f"konsola: {m.text}") if m.type == "error" else None)
        page.on("pageerror", lambda e: bledy.append(f"wyjątek JS: {e}"))

        page.goto(baza, wait_until="networkidle")
        assert page.title() == "Łowca Okazji", page.title()

        # --- wyszukiwanie ---
        page.fill("#fraza", "iphone 15 128gb")
        page.click("#szukaj")
        page.wait_for_selector(".oferta", timeout=20000)

        ceny = [o.query_selector(".cena").inner_text() for o in page.query_selector_all(".oferta")]
        liczby = [int("".join(z for z in c if z.isdigit())) for c in ceny]
        assert liczby == sorted(liczby), f"wyniki nie są po cenie rosnąco: {ceny}"
        assert liczby[0] > 100_00, f"przynęta cenowa przeszła przez filtr: {ceny}"
        print(f"  wyniki: {len(ceny)}, najtańsza {ceny[0]}")

        tresc = page.inner_text("#wyniki")
        assert "używane" in tresc and "uzywane" not in tresc, "stan bez polskich znaków"

        page.select_option("#porzadek", "dopasowanie")
        page.wait_for_timeout(200)
        assert [o.query_selector(".cena").inner_text() for o in page.query_selector_all(".oferta")] != ceny
        print("  sortowanie: zmienia kolejność")

        page.click("#odsiane summary")
        odsiane = page.inner_text("#odsiane")
        for powod in ("kupię", "mediany"):
            assert powod in odsiane, f"brak powodu odrzucenia „{powod}” w odsianych"
        print(f"  odsiane: {odsiane.count('—')} pozycji z powodami")
        assert page.query_selector("a[href*='/api/eksport']"), "brak pobierania CSV"

        if zrzut:
            page.screenshot(path=zrzut, full_page=True)

        # --- obserwowanie ---
        page.click("#obserwuj")
        page.wait_for_selector("#idz-do-obs", timeout=10000)
        page.click("#idz-do-obs")
        page.wait_for_selector(".obserwowany", timeout=10000)
        assert page.inner_text("#licznik") == "1"

        page.click(".sprawdz-jeden")
        page.wait_for_function(
            "() => { const e = document.querySelector('.zmiany');"
            " return e && !e.classList.contains('ukryty') && e.textContent.trim(); }",
            timeout=30000,
        )
        page.wait_for_timeout(800)
        assert "NOWE" in page.inner_text(".zmiany"), "pierwsze sprawdzenie nie pokazało nowych ofert"
        assert "jeszcze nie sprawdzane" not in page.inner_text(".historia"), "wiersz się nie odświeżył"
        print("  1. sprawdzenie: nowe oferty + odświeżony wiersz")

        page.click(".sprawdz-jeden")
        page.wait_for_timeout(2500)
        assert "TANIEJ" in page.inner_text(".zmiany"), "przecena nie została wykryta"
        assert page.query_selector(".historia svg polyline"), "brak wykresu historii"
        print("  2. sprawdzenie: wykryta przecena + wykres")

        page.click(".usun")
        page.wait_for_timeout(1500)
        assert "Nic jeszcze nie obserwujesz" in page.inner_text("#lista-obserwowanych")
        print("  usuwanie: działa")

        # --- ustawienia ---
        page.click("#tab-ustawienia")
        page.wait_for_selector("#u-zrodla input", timeout=10000)
        assert "Jeszcze nie ustawiony" in page.inner_text("#stan-sekretu")

        page.fill("#u-allegro-id", "test-id-123")
        page.fill("#u-allegro-sekret", "tajny-sekret")
        page.fill("#u-grupy", "kupie-sprzedam-warszawa, 998877")
        page.fill("#u-miasto", "Kraków")
        page.click("#zapisz-ustawienia")
        page.wait_for_function(
            "() => document.getElementById('stan-ustawien').textContent.includes('Zapisane')",
            timeout=15000,
        )
        assert page.input_value("#u-allegro-sekret") == "", "sekret został w polu po zapisie"
        assert "Sekret jest zapisany" in page.inner_text("#stan-sekretu")
        assert page.input_value("#u-allegro-id") == "test-id-123"
        print("  ustawienia: zapisane, sekret nie wraca do przeglądarki")

        # Zapisane grupy muszą się pojawić w linkach do Facebooka.
        page.click("#tab-szukaj")
        page.fill("#fraza", "iphone 15 128gb")
        page.click("#szukaj")
        page.wait_for_selector("#fb a", timeout=20000)
        assert "kupie-sprzedam-warszawa" in page.inner_text("#fb"), "grupa z ustawień nie weszła do linków"
        assert "Kraków" in page.inner_text("#fb"), "domyślne miasto nie weszło do linku Marketplace"
        print("  grupy z ustawień: widoczne w linkach FB")

        browser.close()
    return bledy


def main() -> int:
    zrzut = sys.argv[1] if len(sys.argv) > 1 else None
    port = wolny_port()
    dom = tempfile.mkdtemp(prefix="lowca-ui-")
    srodowisko = {
        **os.environ,
        "LOWCA_HOME": dom,
        "PYTHONPATH": str(KORZEN / "src"),
    }
    serwer = subprocess.Popen(
        [sys.executable, "-m", "dealfinder", "serwer", "--port", str(port),
         "--bez-przegladarki", "--pokaz"],
        env=srodowisko, cwd=KORZEN, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )
    baza = f"http://127.0.0.1:{port}/"
    try:
        poczekaj(baza)
        print(f"Przechodzę przez interfejs na {baza}")
        bledy = przejdz(baza, zrzut)
    finally:
        serwer.terminate()
        serwer.wait(timeout=10)

    if bledy:
        print("\nBŁĘDY PRZEGLĄDARKI:")
        for b in bledy:
            print(" -", b)
        return 1
    print("\nInterfejs przeszedł całą ścieżkę bez błędów.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
