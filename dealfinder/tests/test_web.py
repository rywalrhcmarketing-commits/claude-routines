import json
import threading
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from dealfinder import engine, watcher
from dealfinder.config import Config
from dealfinder.engine import SearchOutcome, SourceReport
from dealfinder.providers.facebook import links_for
from dealfinder.web import server as web
from fake import offer


@pytest.fixture
def serwer(tmp_path, monkeypatch):
    monkeypatch.setenv("LOWCA_HOME", str(tmp_path))

    async def fake_search(query, config, *, dump=False, keep_rejected=False):
        offers = [
            offer("olx", 1, "Rower Kross Level 3.0", 1800.0, location="Warszawa"),
            offer("allegro", 2, "Rower Kross Level 3.0 nowy", 2400.0, delivery_price=0.0),
        ]
        rejected = []
        if keep_rejected:
            junk = offer("olx", 3, "Kupię rower Kross", None)
            junk.rejected_because = "ogłoszenie typu „kupie”"
            rejected = [junk]
        return SearchOutcome(
            query=query,
            offers=offers,
            sources=[
                SourceReport("olx", "OLX", 12, 1),
                SourceReport("vinted", "Vinted", 0, 0, error="HTTP 403"),
            ],
            manual_links=links_for(query, config.facebook_groups),
            rejected=rejected,
        )

    # /api/sprawdz idzie przez watcher.run_once, /api/szukaj przez web.search.
    monkeypatch.setattr(web, "search", fake_search)
    monkeypatch.setattr(engine, "search", fake_search)
    monkeypatch.setattr(watcher, "search", fake_search)
    monkeypatch.setattr(watcher, "notify", lambda *a: True)

    web.Handler.config = Config(facebook_groups=["rowery-warszawa"])
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), web.Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=10) as response:
        return response.status, json.loads(response.read())


def post(base, path, payload):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, json.loads(response.read())


def test_strona_sie_serwuje(serwer):
    with urllib.request.urlopen(serwer + "/", timeout=10) as response:
        body = response.read().decode()
    assert response.status == 200
    assert "Łowca Okazji" in body


def test_szukanie_zwraca_ranking_i_stan_zrodel(serwer):
    status, dane = get(serwer, "/api/szukaj?q=rower+kross&cena=1000-3000")
    assert status == 200
    assert [o["source"] for o in dane["oferty"]] == ["olx", "allegro"]
    assert dane["mediana"] == 2100.0
    assert [z["blad"] for z in dane["zrodla"]] == [None, "HTTP 403"]
    assert dane["odrzucone"][0]["powod"].startswith("ogłoszenie")


def test_linki_facebook_zawieraja_grupe_z_konfiguracji(serwer):
    _, dane = get(serwer, "/api/szukaj?q=rower")
    etykiety = [l["etykieta"] for l in dane["linki_facebook"]]
    assert any("rowery-warszawa" in e for e in etykiety)


def test_pusta_fraza_to_blad_400(serwer):
    try:
        get(serwer, "/api/szukaj?q=")
    except urllib.error.HTTPError as exc:
        assert exc.code == 400
        assert "fraz" in json.loads(exc.read())["blad"].casefold()
    else:
        pytest.fail("pusta fraza powinna dać 400")


def test_obserwowanie_zapisuje_i_usuwa(serwer):
    _, dodane = post(serwer, "/api/obserwuj", {"q": "rower kross", "cena": "-3000"})
    assert dodane["id"] > 0

    _, lista = get(serwer, "/api/obserwowane")
    assert [w["fraza"] for w in lista["obserwowane"]] == ["rower kross"]

    _, sprawdzone = get(serwer, "/api/sprawdz")
    assert len(sprawdzone["wyniki"][0]["nowe"]) == 2

    _, znowu = get(serwer, "/api/sprawdz")
    assert znowu["wyniki"][0]["nowe"] == []  # drugi przebieg nie zgłasza tych samych

    _, usuniete = post(serwer, "/api/zapomnij", {"id": dodane["id"]})
    assert usuniete["usuniete"] is True


def test_nieznany_adres_to_404(serwer):
    try:
        get(serwer, "/api/nie-ma")
    except urllib.error.HTTPError as exc:
        assert exc.code == 404
    else:
        pytest.fail("powinno być 404")


def test_sprawdzenie_jednego_obserwowanego(serwer):
    _, a = post(serwer, "/api/obserwuj", {"q": "rower kross"})
    _, b = post(serwer, "/api/obserwuj", {"q": "laptop dell"})

    _, wynik = get(serwer, f"/api/sprawdz?id={a['id']}")
    assert wynik["sprawdzonych"] == 1
    assert wynik["wyniki"][0]["id"] == a["id"]

    _, wszystkie = get(serwer, "/api/sprawdz")
    assert wszystkie["sprawdzonych"] == 2


def test_eksport_csv_ma_bom_i_przecinki_dziesietne(serwer):
    with urllib.request.urlopen(serwer + "/api/eksport?q=rower+kross", timeout=10) as odp:
        surowe = odp.read()
        naglowek = odp.headers["Content-Disposition"]
    assert surowe.startswith(b"\xef\xbb\xbf")  # Excel bez tego łamie ogonki
    assert "oferty-rower-kross.csv" in naglowek

    tekst = surowe.decode("utf-8-sig")
    wiersze = [w for w in tekst.splitlines() if w]
    assert wiersze[0].startswith("cena_calkowita;cena;dostawa;tytul")
    assert len(wiersze) == 3  # nagłówek + dwie oferty
    assert "1800,00" in wiersze[1]
    assert "https://olx.example/1" in wiersze[1]
