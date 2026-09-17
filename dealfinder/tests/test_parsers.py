"""Testy parserów na przykładowych odpowiedziach.

UWAGA: te odpowiedzi są odtworzone z dokumentacji i znanego kształtu API,
nie zrzucone z żywego serwisu. Sprawdzają, że mapowanie pól i odporność na
braki działają — NIE że serwis dziś odpowiada dokładnie tak. Do tego służy
`lowca doktor`.
"""

from dealfinder.models import Condition, OfferKind, parse_price
from dealfinder.providers.allegro import AllegroProvider
from dealfinder.providers.html_cards import extract_cards
from dealfinder.providers.olx import OlxProvider
from dealfinder.providers.vinted import VintedProvider
from dealfinder.query import Query, parse_price_range
from dealfinder.relevance import classify_kind, detect_condition


def test_ceny_z_roznych_zapisow():
    assert parse_price("1 299,00 zł") == 1299.0
    assert parse_price("1.299,00 zł") == 1299.0
    assert parse_price("12 000 zł") == 12000.0
    assert parse_price("19.99") == 19.99
    assert parse_price("Za darmo") is None
    assert parse_price(None) is None


def test_widelki_cenowe():
    assert parse_price_range("500-1500") == (500.0, 1500.0)
    assert parse_price_range("-2000") == (None, 2000.0)
    assert parse_price_range("800-") == (800.0, None)
    assert parse_price_range("") == (None, None)


def test_olx_brakujace_pola_nie_wywracaja_parsera():
    provider = OlxProvider()
    assert provider._to_offer({}) is None
    assert provider._to_offer({"id": 1}) is None  # bez tytułu
    ubogie = provider._to_offer({"id": 7, "title": "Rower", "url": "https://x/1"})
    assert ubogie is not None and ubogie.price is None and ubogie.location is None


def test_olx_cena_z_etykiety_gdy_brak_liczby():
    provider = OlxProvider()
    item = {
        "id": 8, "title": "Rower Kross", "url": "https://x/8",
        "params": [{"key": "price", "value": {"label": "1 250 zł", "negotiable": True}}],
    }
    offer = provider._to_offer(item)
    assert offer.price == 1250.0 and offer.negotiable is True


def test_allegro_darmowa_dostawa_to_zero_a_nie_brak():
    provider = AllegroProvider()
    item = {
        "id": "1", "name": "Rower", "sellingMode": {"price": {"amount": "999.00"}},
        "delivery": {"availableForFree": True},
    }
    offer = provider._to_offer(item)
    assert offer.delivery_price == 0.0 and offer.total_price == 999.0


def test_vinted_dolicza_oplate_do_ceny_koncowej():
    provider = VintedProvider()
    item = {
        "id": 5, "title": "Kurtka Nike", "brand_title": "Nike",
        "price": {"amount": "50.0", "currency_code": "PLN"},
        "total_item_price": {"amount": "57.50"}, "status_id": 3,
        "url": "https://www.vinted.pl/items/5",
    }
    offer = provider._to_offer(item)
    assert offer.price == 50.0 and offer.delivery_price == 7.5
    assert offer.total_price == 57.5
    assert offer.title == "Kurtka Nike"


def test_ekstraktor_kart_laczy_cene_z_wlasciwa_oferta():
    html = """
    <div><article><a href="/oferta/rower-kross-IDaaa"><h3>Rower Kross Level 3.0</h3></a>
      <span>1 800,00 zł</span><span>Warszawa</span></article>
    <article><a href="/oferta/rower-trek-IDbbb"><h3>Rower Trek Marlin</h3></a>
      <span>2 400,00 zł</span><span>Kraków</span></article></div>
    """
    cards = extract_cards(html, base_url="https://x.pl/", offer_path="/oferta/")
    assert [c["price"] for c in cards] == [1800.0, 2400.0]
    assert [c["id"] for c in cards] == ["aaa", "bbb"]
    assert [c["location"] for c in cards] == ["Warszawa", "Kraków"]


def test_ekstraktor_pomija_linki_nawigacyjne():
    html = '<a href="/pomoc">Pomoc</a><a href="/oferta/x-IDzz">Rzecz</a><span>10 zł</span>'
    cards = extract_cards(html, base_url="https://x.pl/", offer_path="/oferta/")
    assert len(cards) == 1 and cards[0]["title"] == "Rzecz"


def test_rozpoznawanie_rodzaju_ogloszenia():
    assert classify_kind("Kupię iPhone 15") is OfferKind.WANTED
    assert classify_kind("Zamienię rower na hulajnogę") is OfferKind.SWAP
    assert classify_kind("Naprawa laptopów Dell") is OfferKind.SERVICE
    assert classify_kind("iPhone 15 128GB sprzedam") is OfferKind.SELL


def test_rozpoznawanie_stanu_z_tytulu():
    assert detect_condition("iPhone 15 NOWY zafoliowany") is Condition.NEW
    assert detect_condition("iPhone 15 uszkodzony, zbita szybka") is Condition.DAMAGED
    assert detect_condition("iPhone 15 128GB") is Condition.UNKNOWN


def test_slug_zapytania_jest_stabilny_i_rozrozniajacy():
    a = Query("iPhone 15", max_price=3000)
    b = Query("iphone 15", max_price=3000)
    c = Query("iphone 15", max_price=2000)
    assert a.slug() == b.slug()
    assert a.slug() != c.slug()


def test_pusta_fraza_odrzucona():
    import pytest
    with pytest.raises(ValueError):
        Query("   ")


def test_liczba_z_tytulu_nie_wchodzi_do_ceny():
    """Regresja: 'Level 3.0' + '1 800,00 zł' dawało 301 800 zł."""
    assert parse_price("Rower Kross Level 3.0 1 800,00 zł Warszawa") == 1800.0
    assert parse_price("iPhone 13 2 450 zł") == 2450.0
    assert parse_price("PlayStation 5 - 1 999 PLN") == 1999.0


def test_kropka_jako_separator_tysiecy_i_jako_przecinek():
    assert parse_price("2.500 zł") == 2500.0      # tysiące
    assert parse_price("19.99") == 19.99          # grosze
    assert parse_price("1899.00") == 1899.0       # grosze (format API Allegro)
