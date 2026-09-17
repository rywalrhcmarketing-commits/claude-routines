"""Facebook: Marketplace i grupy - świadomie BEZ automatu.

Przeszukiwanie Marketplace'u i grup wymaga zalogowanej sesji, jest wprost
niezgodne z regulaminem Facebooka i kończy się blokadą konta. Zamiast tego
budujemy gotowe linki do wyszukiwania - klikasz, jesteś już zalogowany,
widzisz wyniki. Robota bez ryzyka dla Twojego konta.
"""

from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import quote_plus

from ..query import Query

MARKETPLACE = "https://www.facebook.com/marketplace/search/?query={phrase}"
MARKETPLACE_CITY = "https://www.facebook.com/marketplace/{city}/search/?query={phrase}"
GROUP = "https://www.facebook.com/groups/{group}/search/?q={phrase}"


@dataclass(slots=True)
class ManualLink:
    label: str
    url: str
    hint: str


def links_for(query: Query, groups: list[str] | None = None) -> list[ManualLink]:
    """Linki do otwarcia ręcznie. ``groups`` to identyfikatory lub nazwy grup
    z adresu facebook.com/groups/<TO_TUTAJ>/."""
    phrase = quote_plus(query.phrase.strip())
    suffix = ""
    if query.max_price is not None:
        suffix += f"&maxPrice={int(query.max_price)}"
    if query.min_price is not None:
        suffix += f"&minPrice={int(query.min_price)}"

    out: list[ManualLink] = []
    if query.city:
        out.append(
            ManualLink(
                "Marketplace — " + query.city,
                MARKETPLACE_CITY.format(city=quote_plus(query.city.casefold()), phrase=phrase) + suffix,
                "wyniki zawężone do miasta",
            )
        )
    out.append(
        ManualLink(
            "Marketplace",
            MARKETPLACE.format(phrase=phrase) + suffix,
            "cała Polska, sortowanie ustawisz na miejscu",
        )
    )
    for group in groups or []:
        out.append(
            ManualLink(
                f"Grupa: {group}",
                GROUP.format(group=quote_plus(str(group)), phrase=phrase),
                "szukanie w obrębie jednej grupy",
            )
        )
    return out
