"""Dostawca opisany konfiguracją, nie kodem.

Serwis bez API i bez niespodzianek sprowadza się do trzech rzeczy: adresu
wyszukiwarki, fragmentu ścieżki w linku do oferty i nazwy. Dopisanie kolejnego
portalu to jeden wpis, nie nowy plik.
"""

from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import quote_plus

from ..models import Offer
from ..net import Fetcher
from ..query import Query
from ..relevance import classify_kind, detect_condition
from .base import BaseProvider
from .html_cards import extract_cards


@dataclass(slots=True)
class SiteSpec:
    name: str
    label: str
    #: {phrase} zostanie podmienione na zakodowaną frazę.
    search_url: str
    offer_path: str
    base_url: str
    #: Niektóre serwisy chcą frazy z myślnikami zamiast spacji.
    phrase_separator: str = "+"


class GenericHtmlProvider(BaseProvider):
    def __init__(self, spec: SiteSpec) -> None:
        self.spec = spec
        self.name = spec.name
        self.label = spec.label
        self.needs_setup = False

    async def fetch(self, query: Query, fetcher: Fetcher) -> list[Offer]:
        phrase = quote_plus(query.phrase.strip(), safe="")
        if self.spec.phrase_separator != "+":
            phrase = phrase.replace("+", self.spec.phrase_separator)
        url = self.spec.search_url.format(phrase=phrase)

        response = await fetcher.get(url, label=self.spec.name)
        cards = extract_cards(
            response.text,
            base_url=self.spec.base_url,
            offer_path=self.spec.offer_path,
            max_cards=query.limit_per_source,
        )
        if not cards:
            raise ValueError(
                f"{self.spec.label}: nie znalazłem żadnej oferty w HTML-u "
                f"(szukałem linków zawierających '{self.spec.offer_path}'). "
                "Uruchom `lowca doktor --zrzut`, żeby zobaczyć, co przyszło."
            )

        offers = []
        for card in cards:
            title = str(card["title"])
            offers.append(
                Offer(
                    source=self.name,
                    source_id=str(card["id"]),
                    title=title,
                    url=str(card["url"]),
                    price=card["price"],  # type: ignore[arg-type]
                    location=card["location"],  # type: ignore[arg-type]
                    image_url=card["image"],  # type: ignore[arg-type]
                    condition=detect_condition(title),
                    kind=classify_kind(title),
                )
            )
        return offers


ALLEGRO_LOKALNIE = SiteSpec(
    name="allegrolokalnie",
    label="Allegro Lokalnie",
    search_url="https://allegrolokalnie.pl/oferty/q/{phrase}",
    offer_path="/oferta/",
    base_url="https://allegrolokalnie.pl/",
    phrase_separator="-",
)

SPRZEDAJEMY = SiteSpec(
    name="sprzedajemy",
    label="Sprzedajemy.pl",
    search_url="https://sprzedajemy.pl/szukaj?inp_text={phrase}",
    offer_path="/",
    base_url="https://sprzedajemy.pl/",
)
