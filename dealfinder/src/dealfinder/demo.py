"""Tryb pokazowy: przykładowe oferty zamiast prawdziwej sieci.

Po co: żeby dało się zobaczyć, czy aplikacja w ogóle działa, zanim zacznie się
szukać przyczyn w konfiguracji, kluczach albo blokadach serwisów. Gdy dane
pokazowe wyświetlają się poprawnie, wiadomo że problem leży w sieci, nie w
programie.
"""

from __future__ import annotations

from datetime import datetime, timedelta, UTC

from .models import Condition, Offer
from .providers.base import BaseProvider
from .query import Query
from .relevance import classify_kind

#: Ile razy uruchomiono wyszukiwanie - żeby ceny "żyły" między sprawdzeniami.
_RUNS = {"n": 0}

_TOWAR = [
    ("olx", "iPhone 15 128GB czarny, gwarancja do 2027", 2700.0, "Warszawa, Mazowieckie", Condition.USED, None, True),
    ("allegro", "Apple iPhone 15 128GB Black - nowy, FV23", 3199.0, "Gdańsk", Condition.NEW, 0.0, False),
    ("allegrolokalnie", "iPhone 15 128 GB stan idealny, komplet", 2890.0, "Kraków", Condition.USED, 15.0, False),
    ("olx", "iPhone 15 128GB niebieski, bateria 100%", 2649.0, "Łódź", Condition.USED, None, True),
    ("vinted", "iPhone 15 128GB - etui gratis", 2999.0, None, Condition.USED, 12.9, False),
]

_SMIECI = [
    ("Etui do iPhone 15 silikonowe czarne", 29.0),
    ("Szkło hartowane iPhone 15 - 2 sztuki", 15.0),
    ("KUPIĘ iPhone 15 - każdy stan, gotówka", None),
    ("Zamienię iPhone 15 na Samsunga S24", None),
    ("iPhone 15 128GB", 1.0),
    ("Naprawa iPhone 15 - wymiana szybki", 199.0),
]


def demo_offers(query: Query, run: int) -> list[Offer]:
    """Oferty pokazowe. Cena pierwszej spada z każdym przebiegiem, żeby dało
    się zobaczyć, jak wygląda wykryta przecena."""
    rabat = 50.0 * max(0, run - 1)
    now = datetime.now(UTC)
    offers: list[Offer] = []

    for index, (source, title, price, city, condition, delivery, haggle) in enumerate(_TOWAR, start=1):
        if index == 4 and run < 2:
            continue  # ta "pojawia się" dopiero przy kolejnym sprawdzeniu
        offers.append(
            Offer(
                source=source,
                source_id=f"demo-{index}",
                title=title,
                url=f"https://example.com/{source}/oferta-{index}",
                price=round(price - (rabat if index == 1 else 0), 2),
                delivery_price=delivery,
                negotiable=haggle,
                condition=condition,
                location=city,
                seller="sprzedawca_demo",
                published_at=now - timedelta(days=index),
            )
        )

    for index, (title, price) in enumerate(_SMIECI, start=90):
        offers.append(
            Offer(
                source="olx",
                source_id=f"demo-{index}",
                title=title,
                url=f"https://example.com/olx/smiec-{index}",
                price=price,
                location="Warszawa",
                kind=classify_kind(title),
            )
        )
    return offers


class DemoProvider(BaseProvider):
    """Udaje JEDEN serwis. Zwraca oferty i nic więcej - odsiewanie, ranking,
    deduplikacja i wykrywanie przynęt lecą prawdziwym kodem silnika.

    Tak musi być: gdyby tryb pokazowy miał własny potok, pokazywałby coś
    innego niż to, co robi program naprawdę.
    """

    needs_setup = False

    def __init__(self, name: str, label: str) -> None:
        self.name = name
        self.label = label

    async def fetch(self, query: Query, fetcher) -> list[Offer]:
        return [o for o in demo_offers(query, _RUNS["n"]) if o.source == self.name]


DEMO_SOURCES = [
    ("olx", "OLX"),
    ("allegro", "Allegro"),
    ("allegrolokalnie", "Allegro Lokalnie"),
    ("vinted", "Vinted"),
]

NOTE = "TRYB POKAZOWY - to nie są prawdziwe oferty, tylko przykład działania."


def enable() -> None:
    """Podmienia tylko dostawców - potok wyszukiwania zostaje prawdziwy."""
    from . import engine, watcher
    from .web import server

    prawdziwe_search = engine.search

    def demo_providers(config, only=None):
        _RUNS["n"] += 1
        wanted = [n.casefold() for n in only] if only else None
        return [
            DemoProvider(name, label)
            for name, label in DEMO_SOURCES
            if wanted is None or name in wanted
        ]

    async def search_z_uwaga(query, config, **kw):
        outcome = await prawdziwe_search(query, config, **kw)
        outcome.notes = [NOTE, *outcome.notes]
        return outcome

    engine.build_providers = demo_providers
    engine.search = search_z_uwaga
    watcher.search = search_z_uwaga
    server.search = search_z_uwaga
