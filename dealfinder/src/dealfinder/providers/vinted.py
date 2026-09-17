"""Vinted - endpoint katalogu wymaga ciasteczka sesji, które strona wydaje
każdemu odwiedzającemu. Pobieramy je jednym wejściem na stronę główną."""

from __future__ import annotations

from typing import Any

from ..models import Condition, Offer, parse_price
from ..net import Fetcher, load_json
from ..query import Query
from ..relevance import classify_kind
from .base import BaseProvider

HOME = "https://www.vinted.pl/"
CATALOG = "https://www.vinted.pl/api/v2/catalog/items"

#: status_id z Vinted -> nasz stan
_STATUS = {
    6: Condition.NEW,   # Nowy z metką
    1: Condition.NEW,   # Nowy bez metki
    2: Condition.USED,  # Bardzo dobry
    3: Condition.USED,  # Dobry
    4: Condition.USED,  # Zadowalający
}


class VintedProvider(BaseProvider):
    name = "vinted"
    label = "Vinted"

    async def fetch(self, query: Query, fetcher: Fetcher) -> list[Offer]:
        await fetcher.get(HOME, label="vinted-home")  # tylko po ciasteczko sesji

        params: dict[str, Any] = {
            "search_text": query.phrase,
            "per_page": min(query.limit_per_source, 96),
            "order": "price_low_to_high",
        }
        if query.min_price is not None:
            params["price_from"] = int(query.min_price)
        if query.max_price is not None:
            params["price_to"] = int(query.max_price)

        response = await fetcher.get(
            CATALOG,
            params=params,
            headers={"Accept": "application/json", "Referer": HOME},
            label="vinted-catalog",
        )
        payload = load_json(response)
        items = payload.get("items") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            raise ValueError("Vinted: odpowiedź bez pola 'items'")
        return [offer for item in items if (offer := self._to_offer(item)) is not None]

    def _to_offer(self, item: Any) -> Offer | None:
        if not isinstance(item, dict) or not item.get("id"):
            return None
        title = (item.get("title") or "").strip()
        if not title:
            return None

        price = parse_price(_amount(item.get("price")))
        # Vinted dolicza "ochronę kupującego" - bez niej cena jest nieprawdziwa.
        total = parse_price(_amount(item.get("total_item_price")))
        fee = round(total - price, 2) if (total is not None and price is not None and total > price) else None

        brand = (item.get("brand_title") or "").strip()
        full_title = f"{brand} {title}".strip() if brand and brand.casefold() not in title.casefold() else title

        return Offer(
            source=self.name,
            source_id=str(item["id"]),
            title=full_title,
            url=item.get("url") or f"https://www.vinted.pl/items/{item['id']}",
            price=price,
            currency=(item.get("price") or {}).get("currency_code", "PLN") if isinstance(item.get("price"), dict) else "PLN",
            delivery_price=fee,
            condition=_STATUS.get(item.get("status_id"), Condition.UNKNOWN),
            kind=classify_kind(full_title),
            seller=(item.get("user") or {}).get("login") if isinstance(item.get("user"), dict) else None,
            image_url=(item.get("photo") or {}).get("url") if isinstance(item.get("photo"), dict) else None,
            raw={"id": item.get("id")},
        )


def _amount(value: Any) -> Any:
    """Cena bywa liczbą albo obiektem {amount, currency_code}."""
    if isinstance(value, dict):
        return value.get("amount")
    return value
