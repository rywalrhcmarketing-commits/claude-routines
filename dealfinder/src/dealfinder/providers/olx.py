"""OLX.pl - ten sam endpoint JSON, z którego korzysta ich własna strona.

Publicznego API dla osób trzecich OLX nie udostępnia. Odpytujemy wolno
(limit tempa w ``net.Fetcher``), bez obchodzenia zabezpieczeń: gdy serwis
odpowie 403, dostawca zwraca błąd i wyszukiwanie leci dalej bez niego.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from ..models import Condition, Offer, parse_price
from ..net import Fetcher, load_json
from ..query import Query
from ..relevance import classify_kind, detect_condition
from .base import BaseProvider
from .html_cards import extract_cards

API = "https://www.olx.pl/api/v1/offers/"
WEB = "https://www.olx.pl/oferty/q-{phrase}/"

_STATE_TO_CONDITION = {
    "new": Condition.NEW,
    "used": Condition.USED,
    "damaged": Condition.DAMAGED,
}


class OlxProvider(BaseProvider):
    name = "olx"
    label = "OLX"

    async def fetch(self, query: Query, fetcher: Fetcher) -> list[Offer]:
        params: dict[str, Any] = {
            "offset": 0,
            "limit": min(query.limit_per_source, 50),
            "query": query.phrase,
            "sort_by": "filter_float_price:asc",
        }
        if query.min_price is not None:
            params["filter_float_price:from"] = int(query.min_price)
        if query.max_price is not None:
            params["filter_float_price:to"] = int(query.max_price)

        response = await fetcher.get(API, params=params, label="olx-api")
        payload = load_json(response)
        items = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            raise ValueError("OLX: odpowiedź bez pola 'data' - zmienił się format API")
        return [offer for item in items if (offer := self._to_offer(item)) is not None]

    async def fetch_html_fallback(self, query: Query, fetcher: Fetcher) -> list[Offer]:
        """Gdy endpoint JSON przestanie działać - zwykła strona wyników."""
        url = WEB.format(phrase=query.phrase.replace(" ", "-"))
        response = await fetcher.get(url, label="olx-html")
        offers = []
        for card in extract_cards(response.text, base_url=url, offer_path="/d/oferta/"):
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

    def _to_offer(self, item: dict[str, Any]) -> Offer | None:
        if not isinstance(item, dict) or not item.get("id"):
            return None
        title = (item.get("title") or "").strip()
        if not title:
            return None

        params = _params_by_key(item.get("params"))
        price_param = params.get("price") or {}
        price = parse_price(price_param.get("value"))
        if price is None:
            price = parse_price(price_param.get("label"))

        state = (params.get("state") or {}).get("key")
        condition = _STATE_TO_CONDITION.get(str(state), Condition.UNKNOWN)

        return Offer(
            source=self.name,
            source_id=str(item["id"]),
            title=title,
            url=item.get("url") or f"https://www.olx.pl/d/oferta/{item['id']}/",
            price=price,
            currency=price_param.get("currency") or "PLN",
            negotiable=bool(price_param.get("negotiable")),
            condition=detect_condition(title, condition),
            kind=classify_kind(title, item.get("description") or ""),
            location=_location_of(item),
            seller=(item.get("user") or {}).get("name"),
            image_url=_photo_of(item),
            published_at=_time_of(item.get("created_time") or item.get("last_refresh_time")),
            raw={"id": item.get("id")},
        )


def _params_by_key(params: Any) -> dict[str, dict[str, Any]]:
    """OLX podaje atrybuty jako listę {key, value}; wygodniej jako słownik."""
    out: dict[str, dict[str, Any]] = {}
    if not isinstance(params, list):
        return out
    for entry in params:
        if not isinstance(entry, dict):
            continue
        key = entry.get("key")
        value = entry.get("value")
        if isinstance(key, str):
            out[key] = value if isinstance(value, dict) else {"value": value}
    return out


def _location_of(item: dict[str, Any]) -> str | None:
    location = item.get("location")
    if not isinstance(location, dict):
        return None
    city = (location.get("city") or {}).get("name")
    region = (location.get("region") or {}).get("name")
    return ", ".join(part for part in (city, region) if part) or None


def _photo_of(item: dict[str, Any]) -> str | None:
    photos = item.get("photos")
    if isinstance(photos, list) and photos:
        first = photos[0]
        link = first.get("link") if isinstance(first, dict) else first
        if isinstance(link, str):
            return link.replace("{width}", "640").replace("{height}", "480")
    return None


def _time_of(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
