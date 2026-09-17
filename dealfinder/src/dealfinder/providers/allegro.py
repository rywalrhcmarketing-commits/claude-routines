"""Allegro - oficjalne REST API. Jedyne źródło tutaj z błogosławieństwem serwisu.

Wymaga darmowej rejestracji aplikacji na apps.developer.allegro.pl i wpisania
client_id / client_secret do konfiguracji. Token bierzemy w trybie
client_credentials - wystarcza do czytania publicznych ofert.
"""

from __future__ import annotations

import time
from typing import Any

from ..models import Condition, Offer, parse_price
from ..net import Fetcher, load_json
from ..query import Query
from ..relevance import classify_kind, detect_condition
from .base import BaseProvider

TOKEN_URL = "https://allegro.pl/auth/oauth/token"
LISTING_URL = "https://api.allegro.pl/offers/listing"
ACCEPT = "application/vnd.allegro.public.v1+json"
OFFER_URL = "https://allegro.pl/oferta/{id}"

#: Nazwa parametru ze stanem przedmiotu (Allegro podaje go w 'parameters').
_CONDITION_PARAM = "stan"
_CONDITION_VALUES = {
    "nowy": Condition.NEW,
    "nowy bez metki": Condition.NEW,
    "używany": Condition.USED,
    "uzywany": Condition.USED,
    "uszkodzony": Condition.DAMAGED,
}


class AllegroProvider(BaseProvider):
    name = "allegro"
    label = "Allegro"
    needs_setup = True

    def __init__(self, client_id: str | None = None, client_secret: str | None = None) -> None:
        self.client_id = client_id
        self.client_secret = client_secret
        self._token: str | None = None
        self._token_expires_at: float = 0.0

    @property
    def configured(self) -> bool:
        return bool(self.client_id and self.client_secret)

    async def fetch(self, query: Query, fetcher: Fetcher) -> list[Offer]:
        if not self.configured:
            raise RuntimeError(
                "Brak client_id/client_secret Allegro - zobacz README, sekcja „Allegro API”"
            )
        token = await self._access_token(fetcher)
        params: dict[str, Any] = {
            "phrase": query.phrase,
            "limit": min(query.limit_per_source, 60),
            "sort": "+price",
        }
        if query.min_price is not None:
            params["price.from"] = query.min_price
        if query.max_price is not None:
            params["price.to"] = query.max_price
        if query.condition is Condition.NEW:
            params["parameter.11323"] = "11323_1"  # Stan: Nowy

        response = await fetcher.get(
            LISTING_URL,
            params=params,
            headers={"Authorization": f"Bearer {token}", "Accept": ACCEPT},
            label="allegro-listing",
        )
        payload = load_json(response)
        items = payload.get("items") if isinstance(payload, dict) else None
        if not isinstance(items, dict):
            raise ValueError("Allegro: odpowiedź bez pola 'items'")

        offers: list[Offer] = []
        for bucket in ("promoted", "regular"):
            for item in items.get(bucket) or []:
                offer = self._to_offer(item)
                if offer is not None:
                    offers.append(offer)
        return offers

    async def _access_token(self, fetcher: Fetcher) -> str:
        if self._token and time.time() < self._token_expires_at - 60:
            return self._token
        response = await fetcher.post(
            TOKEN_URL,
            data={"grant_type": "client_credentials"},
            auth=(self.client_id or "", self.client_secret or ""),
        )
        payload = load_json(response)
        token = payload.get("access_token")
        if not token:
            raise RuntimeError("Allegro nie oddał tokenu - sprawdź client_id i secret")
        self._token = str(token)
        self._token_expires_at = time.time() + float(payload.get("expires_in", 3600))
        return self._token

    def _to_offer(self, item: Any) -> Offer | None:
        if not isinstance(item, dict) or not item.get("id"):
            return None
        title = (item.get("name") or "").strip()
        if not title:
            return None

        selling = item.get("sellingMode") or {}
        price = parse_price((selling.get("price") or {}).get("amount"))
        currency = (selling.get("price") or {}).get("currency") or "PLN"

        delivery = item.get("delivery") or {}
        if delivery.get("availableForFree"):
            delivery_price: float | None = 0.0
        else:
            delivery_price = parse_price((delivery.get("lowestPrice") or {}).get("amount"))

        return Offer(
            source=self.name,
            source_id=str(item["id"]),
            title=title,
            url=OFFER_URL.format(id=item["id"]),
            price=price,
            currency=currency,
            delivery_price=delivery_price,
            condition=detect_condition(title, _condition_of(item)),
            kind=classify_kind(title),
            location=(item.get("location") or {}).get("city"),
            seller=(item.get("seller") or {}).get("login"),
            image_url=_image_of(item),
            raw={"id": item.get("id")},
        )


def _condition_of(item: dict[str, Any]) -> Condition:
    for param in item.get("parameters") or []:
        if not isinstance(param, dict):
            continue
        if str(param.get("name", "")).strip().casefold() != _CONDITION_PARAM:
            continue
        values = param.get("values") or []
        if values:
            return _CONDITION_VALUES.get(str(values[0]).strip().casefold(), Condition.UNKNOWN)
    return Condition.UNKNOWN


def _image_of(item: dict[str, Any]) -> str | None:
    images = item.get("images")
    if isinstance(images, list) and images:
        first = images[0]
        if isinstance(first, dict):
            return first.get("url")
        if isinstance(first, str):
            return first
    return None
