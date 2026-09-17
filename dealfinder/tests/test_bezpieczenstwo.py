"""Dane wchodzą z cudzych serwisów - traktujemy je jak wrogie.

Tytuł i adres oferty pochodzą z ogłoszenia, które ktoś obcy mógł spreparować.
Escapowanie HTML-a chroni przed wyjściem z atrybutu, ale NIE przed adresem
"javascript:", który wykonuje się po kliknięciu w link.
"""

import pytest

from dealfinder.models import Offer, safe_url
from dealfinder.providers.html_cards import extract_cards
from dealfinder.query import Query
from dealfinder.relevance import judge


@pytest.mark.parametrize(
    "adres",
    [
        "javascript:alert(document.cookie)",
        "JavaScript:alert(1)",
        "java\tscript:alert(1)",
        " javascript:alert(1) ",
        "data:text/html,<script>alert(1)</script>",
        "vbscript:msgbox(1)",
        "mailto:ktos@example.com",
        "file:///etc/passwd",
        "",
        None,
    ],
)
def test_odrzucamy_wszystko_co_nie_jest_http(adres):
    assert safe_url(adres) is None


@pytest.mark.parametrize(
    "adres",
    [
        "https://www.olx.pl/d/oferta/x-IDabc.html",
        "http://allegrolokalnie.pl/oferta/y",
        "https://example.com/a?b=c&d=e#f",
    ],
)
def test_zwykle_linki_przechodza(adres):
    assert safe_url(adres) == adres


def test_oferta_czysci_adres_przy_tworzeniu():
    zla = Offer(
        source="olx",
        source_id="1",
        title="iPhone 15 128GB OKAZJA",
        url="javascript:alert(1)",
        price=1999.0,
        image_url="javascript:alert(2)",
    )
    assert zla.url == ""
    assert zla.image_url is None


def test_oferta_bez_adresu_wypada_z_wynikow():
    zla = Offer(source="olx", source_id="1", title="iPhone 15 128GB", url="javascript:alert(1)", price=1999.0)
    werdykt = judge(zla, Query("iphone 15 128gb"))
    assert not werdykt.keep and "adresu" in werdykt.reason


def test_ekstraktor_pomija_linki_ze_zlym_schematem():
    """Regresja: spreparowane ogłoszenie podstawiało javascript: w href."""
    html = """
    <article><a href="javascript:alert(document.cookie)"><h3>iPhone 15 128GB OKAZJA</h3></a>
      <span>1 999 zł</span></article>
    <article><a href="/oferta/iphone-15-IDbbb"><h3>iPhone 15 128GB zwykły</h3></a>
      <span>2 700 zł</span></article>
    """
    karty = extract_cards(html, base_url="https://www.olx.pl/", offer_path="")
    assert [k["url"] for k in karty] == ["https://www.olx.pl/oferta/iphone-15-IDbbb"]


def test_tytul_z_html_em_nie_psuje_eksportu_csv():
    """Tytuł z cudzysłowami i średnikami nie może rozwalić kolumn w CSV."""
    import csv
    import io

    zly_tytul = 'iPhone 15 "OKAZJA"; DROP TABLE; <script>alert(1)</script>'
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";")
    w.writerow(["tytul", "link"])
    w.writerow([zly_tytul, "https://example.com/1"])
    wiersze = list(csv.reader(io.StringIO(buf.getvalue()), delimiter=";"))
    assert wiersze[1][0] == zly_tytul  # wraca w całości, w jednej komórce
    assert len(wiersze[1]) == 2
