"""Odsiewanie śmieci. Bez tego 'iPhone 15' to w połowie etui i 'kupię'."""

from __future__ import annotations

import re
import statistics
from dataclasses import dataclass

from .models import Condition, Offer, OfferKind, normalize_text
from .query import Query

#: Ogłoszenia, które nie są sprzedażą przedmiotu.
_WANTED = re.compile(r"\b(kupie|kupię|szukam|poszukuje|poszukuję|przyjme|przyjmę|odkupie|odkupię)\b")
_SWAP = re.compile(r"\b(zamienie|zamienię|zamiana|zamiane|zamianę|oddam za)\b")
_SERVICE = re.compile(r"\b(naprawa|serwis|wymiana szybki|wymiana ekranu|skup|lombard|wynajem|wypozyczalnia)\b")

#: Akcesoria i części - najczęstszy fałszywy trop przy elektronice.
ACCESSORY_WORDS = {
    "etui", "case", "obudowa", "pokrowiec", "futeral", "szklo", "szkło",
    "folia", "ochronne", "ladowarka", "ładowarka", "kabel", "przewod",
    "adapter", "uchwyt", "podstawka", "pasek", "smycz", "naklejka",
    "czesci", "części", "podzespoly", "plyta glowna", "bateria", "wyswietlacz",
    "klapka", "ramka", "zaslepka", "torba", "plecak", "stojak",
}

#: Stan przedmiotu wyczytany z tytułu.
_DAMAGED = re.compile(r"\b(uszkodzony|uszkodzone|uszkodzona|niesprawny|nie dziala|nie działa|na czesci|na części|zbita|pekniety|pęknięty|zalany)\b")
_NEW = re.compile(r"\b(nowy|nowa|nowe|nieuzywany|nieużywany|zafoliowany|fabrycznie nowy)\b")


@dataclass(slots=True)
class Verdict:
    keep: bool
    reason: str | None = None
    #: 0..1 - jak dobrze tytuł odpowiada frazie
    match: float = 0.0


def classify_kind(title: str, description: str = "") -> OfferKind:
    text = normalize_text(f"{title} {description}")
    if _WANTED.search(text):
        return OfferKind.WANTED
    if _SWAP.search(text):
        return OfferKind.SWAP
    if _SERVICE.search(text):
        return OfferKind.SERVICE
    return OfferKind.SELL


def detect_condition(title: str, fallback: Condition = Condition.UNKNOWN) -> Condition:
    text = normalize_text(title)
    if _DAMAGED.search(text):
        return Condition.DAMAGED
    if _NEW.search(text):
        return Condition.NEW
    return fallback


#: "iPhone 15 + etui gratis" to telefon z dodatkiem, nie etui.
BONUS_MARKERS = {"gratis", "zestawie", "komplecie", "prezent", "dodatku", "dodatkowo", "bonus"}


def _accessory_hit(title: str, query_words: set[str]) -> str | None:
    """Akcesorium tylko wtedy, gdy user sam o nie nie prosił - i gdy nie jest
    wymienione jako dodatek do właściwego towaru."""
    words = title.split()
    # "w zestawie szkło i etui" wylicza dodatki - wszystko po znaczniku jest
    # dodatkiem, nie tylko słowo tuż obok niego.
    marker = next((i for i, w in enumerate(words) if w in BONUS_MARKERS), None)
    for position, word in enumerate(words):
        if word not in ACCESSORY_WORDS or word in query_words:
            continue
        if marker is not None and position > marker:
            continue
        if set(words[position + 1 : position + 3]) & BONUS_MARKERS:
            continue  # "etui gratis" - znacznik stoi tuż za dodatkiem
        return word
    return None


def judge(offer: Offer, query: Query) -> Verdict:
    """Pojedyncza oferta kontra zapytanie. Nie patrzy na resztę wyników."""
    title_norm = normalize_text(offer.title)
    query_words = set(normalize_text(query.phrase).split())

    if offer.kind is not OfferKind.SELL:
        return Verdict(False, f"ogłoszenie typu „{offer.kind.value}”")

    for word in query.excluded:
        if word and word in title_norm:
            return Verdict(False, f"wykluczone słowo „{word}”")

    hits = sum(1 for word in query.required if word in title_norm)
    total = len(query.required) or 1
    match = hits / total
    if hits < total:
        missing = [w for w in query.required if w not in title_norm]
        return Verdict(False, f"brak w tytule: {', '.join(missing)}", match)

    accessory = _accessory_hit(title_norm, query_words)
    if accessory:
        return Verdict(False, f"akcesorium („{accessory}”)", match)

    if query.condition is Condition.NEW and offer.condition is Condition.DAMAGED:
        return Verdict(False, "uszkodzony, a szukasz nowego", match)
    if query.condition and offer.condition is not Condition.UNKNOWN:
        if query.condition is not offer.condition and query.condition is not Condition.USED:
            return Verdict(False, f"stan „{offer.condition.value}”", match)

    price = offer.total_price
    if price is not None:
        if query.min_price is not None and price < query.min_price:
            return Verdict(False, "poniżej widełek", match)
        if query.max_price is not None and price > query.max_price:
            return Verdict(False, "powyżej widełek", match)

    return Verdict(True, None, match)


#: Poniżej tego ułamka mediany oferta to prawie na pewno nie ten przedmiot.
BAIT_RATIO = 0.25
#: Przy małej próbce mediana jest chwiejna, więc próg jest dużo ostrzejszy -
#: łapie tylko jawne przynęty (1 zł przy medianie 2 900), a nie tanie okazje.
BAIT_RATIO_SMALL = 0.05
SMALL_SAMPLE = 3


def drop_bait(offers: list[Offer]) -> tuple[list[Offer], list[Offer]]:
    """Odsiewa 'iPhone 15 - 1 zł' i inne przynęty, licząc względem mediany.

    Zwraca (zostaje, odrzucone) - odrzucone trafiają na listę powodów obok
    reszty odsianych, żeby nie znikały bez śladu.

    Próg zależy od wielkości próbki: przy pięciu i więcej ofertach mediana jest
    wiarygodna i ucinamy poniżej 25%; przy trzech-czterech bierzemy tylko
    jawne przynęty, żeby nie wyrzucić prawdziwej okazji.
    """
    priced = [o for o in offers if o.total_price is not None and o.total_price > 0]
    if len(priced) < SMALL_SAMPLE:
        return offers, []
    median = statistics.median(o.total_price for o in priced)
    floor = median * (BAIT_RATIO if len(priced) >= 5 else BAIT_RATIO_SMALL)
    kept: list[Offer] = []
    dropped: list[Offer] = []
    for offer in offers:
        price = offer.total_price
        if price is not None and 0 < price < floor:
            offer.rejected_because = f"podejrzanie tanio wobec mediany {median:.0f} zł"
            dropped.append(offer)
            continue
        kept.append(offer)
    return kept, dropped
