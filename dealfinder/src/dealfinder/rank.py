"""Deduplikacja i kolejność wyników."""

from __future__ import annotations

from difflib import SequenceMatcher

from .models import Condition, Offer, normalize_text
from .query import Query

#: Powyżej tego podobieństwa tytułów (przy zbliżonej cenie) uznajemy, że to
#: ta sama rzecz wystawiona w dwóch serwisach.
SAME_OFFER_TITLE_RATIO = 0.87
SAME_OFFER_PRICE_TOLERANCE = 0.02


def deduplicate(offers: list[Offer]) -> list[Offer]:
    """Zostawia tańszy egzemplarz duplikatu. Ten sam sprzedawca wystawia
    często na OLX i Allegro Lokalnie naraz - dwa razy to samo w wynikach
    tylko zaśmieca listę."""
    kept: list[Offer] = []
    for offer in sorted(offers, key=_cheapest_first):
        if any(_same_thing(offer, other) for other in kept):
            continue
        kept.append(offer)
    return kept


def _cheapest_first(offer: Offer) -> tuple[int, float]:
    price = offer.total_price
    return (1, 0.0) if price is None else (0, price)


def _same_thing(a: Offer, b: Offer) -> bool:
    if a.source == b.source and a.source_id == b.source_id:
        return True
    if a.fingerprint == b.fingerprint:
        return True
    pa, pb = a.total_price, b.total_price
    if pa is None or pb is None or pa <= 0 or pb <= 0:
        return False
    if abs(pa - pb) / max(pa, pb) > SAME_OFFER_PRICE_TOLERANCE:
        return False
    ratio = SequenceMatcher(None, normalize_text(a.title), normalize_text(b.title)).ratio()
    return ratio >= SAME_OFFER_TITLE_RATIO


def score(offer: Offer, query: Query, cheapest: float | None, match: float) -> float:
    """0..100. Cena waży najwięcej, ale nie wszystko - oferta bez ceny albo
    ze stanem niezgodnym z zapytaniem nie ma prawa wygrać z sensowną."""
    points = 40.0 * match

    price = offer.total_price
    if price is not None and cheapest is not None and price > 0:
        # Najtańsza dostaje 45 pkt, dwa razy droższa ~22, trzy razy ~15.
        points += 45.0 * (cheapest / price)
    elif price is None:
        points -= 10.0

    if query.condition and offer.condition is query.condition:
        points += 6.0
    if offer.condition is Condition.DAMAGED and query.condition is not Condition.DAMAGED:
        points -= 15.0

    if query.city and offer.location:
        if normalize_text(query.city) in normalize_text(offer.location):
            points += 9.0

    if offer.delivery_price == 0.0:
        points += 3.0

    return round(max(0.0, min(100.0, points)), 1)


def sort_offers(offers: list[Offer]) -> list[Offer]:
    """Najtańsze na górze; przy równej cenie decyduje dopasowanie."""
    return sorted(
        offers,
        key=lambda o: (
            o.total_price if o.total_price is not None else float("inf"),
            -o.score,
        ),
    )
