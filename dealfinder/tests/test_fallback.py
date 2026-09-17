"""Ścieżka zapasowa OLX: gdy endpoint JSON zawiedzie, wchodzi strona wyników."""

import httpx
import pytest

from dealfinder.net import Blocked
from dealfinder.providers.olx import API, OlxProvider
from dealfinder.query import Query

STRONA = """
<div>
  <article><a href="/d/oferta/rower-kross-level-CID767-IDaaa.html">
     <h4>Rower Kross Level 3.0 rozmiar M</h4></a>
     <p>1 800,00 zł</p><span>Warszawa, Mazowieckie</span></article>
  <article><a href="/d/oferta/rower-trek-CID767-IDbbb.html">
     <h4>Rower Trek Marlin 5</h4></a>
     <p>2 400 zł</p><span>Kraków</span></article>
  <a href="/pomoc/">Pomoc</a>
</div>
"""


class FakeFetcher:
    """Udaje sieć: pierwszy adres pada, drugi oddaje HTML."""

    def __init__(self, json_error, html=STRONA):
        self.json_error = json_error
        self.html = html
        self.calls = []

    async def get(self, url, *, params=None, headers=None, label=None):
        self.calls.append(url)
        if url == API:
            raise self.json_error
        return httpx.Response(200, text=self.html, request=httpx.Request("GET", url))


@pytest.mark.asyncio
async def test_zly_json_przelacza_na_strone_wynikow():
    fetcher = FakeFetcher(ValueError("OLX zwrócił coś, co nie jest JSON-em"))
    offers = await OlxProvider().fetch(Query("rower kross"), fetcher)

    assert len(fetcher.calls) == 2  # najpierw JSON, potem HTML
    assert [o.source_id for o in offers] == ["aaa", "bbb"]
    assert [o.price for o in offers] == [1800.0, 2400.0]
    assert offers[0].location == "Warszawa, Mazowieckie"
    assert all(o.source == "olx" for o in offers)


@pytest.mark.asyncio
async def test_blad_sieci_tez_przelacza_na_html():
    fetcher = FakeFetcher(httpx.ReadTimeout("za wolno"))
    offers = await OlxProvider().fetch(Query("rower kross"), fetcher)
    assert len(offers) == 2


@pytest.mark.asyncio
async def test_odmowa_dostepu_nie_dobija_serwisu_druga_probe():
    """403 dotknie tak samo HTML - drugiego zapytania nie wysyłamy."""
    fetcher = FakeFetcher(Blocked("www.olx.pl odmówił dostępu (HTTP 403)"))
    with pytest.raises(Blocked):
        await OlxProvider().fetch(Query("rower kross"), fetcher)
    assert len(fetcher.calls) == 1


@pytest.mark.asyncio
async def test_gdy_obie_sciezki_zawioda_blad_mowi_co_robic():
    fetcher = FakeFetcher(ValueError("zepsuty JSON"), html="<html><body>nic</body></html>")
    with pytest.raises(ValueError, match="doktor"):
        await OlxProvider().fetch(Query("rower kross"), fetcher)
    assert len(fetcher.calls) == 2


@pytest.mark.asyncio
async def test_dostawca_zamienia_wyjatek_na_raport_a_nie_wywrotke():
    fetcher = FakeFetcher(ValueError("zepsuty JSON"), html="<html>nic</html>")
    result = await OlxProvider().search(Query("rower kross"), fetcher)
    assert result.ok is False
    assert "doktor" in result.error
    assert result.offers == []
