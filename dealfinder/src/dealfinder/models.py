"""Wspólny model oferty - każde źródło sprowadzamy do tego kształtu."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field, asdict
from datetime import datetime, UTC
from enum import StrEnum
from typing import Any


class Condition(StrEnum):
    NEW = "nowe"
    USED = "uzywane"
    DAMAGED = "uszkodzone"
    UNKNOWN = "nieznany"

    @property
    def label(self) -> str:
        """Do pokazania człowiekowi. Sama wartość zostaje bez ogonków, bo
        wchodzi do slugów zapytań i do argumentów wiersza poleceń."""
        return _CONDITION_LABELS[self]


_CONDITION_LABELS = {}  # wypełnione pod definicją klasy


class OfferKind(StrEnum):
    """Czy ktoś sprzedaje, czy szuka. Na OLX ogłoszeń 'kupię' jest mnóstwo."""

    SELL = "sprzedam"
    WANTED = "kupie"
    SWAP = "zamienie"
    SERVICE = "usluga"


_CONDITION_LABELS.update(
    {
        Condition.NEW: "nowe",
        Condition.USED: "używane",
        Condition.DAMAGED: "uszkodzone",
        Condition.UNKNOWN: "nieznany",
    }
)


@dataclass(slots=True)
class Offer:
    source: str
    source_id: str
    title: str
    url: str
    price: float | None
    currency: str = "PLN"
    delivery_price: float | None = None
    negotiable: bool = False
    condition: Condition = Condition.UNKNOWN
    kind: OfferKind = OfferKind.SELL
    location: str | None = None
    seller: str | None = None
    image_url: str | None = None
    published_at: datetime | None = None
    fetched_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    raw: dict[str, Any] = field(default_factory=dict, repr=False)

    # wypełniane przez silnik, nie przez dostawcę
    score: float = 0.0
    rejected_because: str | None = None

    @property
    def total_price(self) -> float | None:
        """Cena, którą realnie zapłacisz: towar + wysyłka."""
        if self.price is None:
            return None
        return round(self.price + (self.delivery_price or 0.0), 2)

    @property
    def key(self) -> str:
        return f"{self.source}:{self.source_id}"

    @property
    def fingerprint(self) -> str:
        """Odcisk do wykrywania tej samej oferty wystawionej w kilku serwisach."""
        words = sorted(set(normalize_text(self.title).split()))
        price_bucket = int(self.price) if self.price is not None else -1
        payload = f"{' '.join(words)}|{price_bucket}"
        return hashlib.sha1(payload.encode("utf-8")).hexdigest()[:16]

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d.pop("raw", None)
        d["condition"] = self.condition.value
        d["condition_label"] = self.condition.label
        d["kind"] = self.kind.value
        d["total_price"] = self.total_price
        for stamp in ("published_at", "fetched_at"):
            value = d.get(stamp)
            d[stamp] = value.isoformat() if isinstance(value, datetime) else None
        return d


_PL_MAP = str.maketrans("ąćęłńóśźż", "acelnoszz")
_NON_WORD = re.compile(r"[^\w\s]+", re.UNICODE)
_SPACES = re.compile(r"\s+")
#: Jednostki pisane raz ze spacją, raz bez: "128 GB" i "128GB" to to samo.
_UNITS = "gb|tb|mb|kb|gib|tib|mah|kg|ml|cm|mm|cali|cal|szt|ghz|mhz|hz|kw|w|l|g|m"
_NUMBER_UNIT = re.compile(rf"(?<=\d)\s+({_UNITS})\b")


def normalize_text(text: str) -> str:
    """Do porównań: bez ogonków, bez interpunkcji, małe litery."""
    lowered = text.casefold().translate(_PL_MAP)
    lowered = _NON_WORD.sub(" ", lowered)
    lowered = _SPACES.sub(" ", lowered).strip()
    # Sklejamy PO usunięciu interpunkcji, żeby "128 GB." też trafiło.
    return _NUMBER_UNIT.sub(r"\1", lowered)


#: Liczba w zapisie cenowym. Grupa tysięcy musi mieć dokładnie trzy cyfry -
#: bez tego "Level 3.0 1 800,00 zł" skleja się w 301800.
_PRICE = re.compile(r'(?<![\d,.])(\d{1,3}(?:[ .  ]?\d{3})*(?:[,.]\d{1,2})?)(?![\d])')
#: 2.500 albo 1.299.000 - kropka w roli separatora tysięcy, nie przecinka.
_DOT_THOUSANDS = re.compile(r'\d{1,3}(?:\.\d{3})+')
#: Gdy w tekście jest waluta, cenę bierzemy sprzed niej - nie pierwszą
#: liczbę z brzegu, bo w "iPhone 13 2 450 zł" tą pierwszą jest 13.
_PRICE_CURRENCY = re.compile(r'(?<![\d,.])(\d{1,3}(?:[ .  ]?\d{3})*(?:[,.]\d{1,2})?)\s*(?:zł|zl|PLN)\b', re.IGNORECASE)


def parse_price(text: str | float | int | None) -> float | None:
    """'1 299,00 zł' -> 1299.0. Zwraca None, gdy ceny nie ma ('Za darmo' też)."""
    if text is None:
        return None
    if isinstance(text, (int, float)):
        return float(text)
    cleaned = text.replace(" ", " ").replace(" ", " ")
    if not any(ch.isdigit() for ch in cleaned):
        return None
    match = _PRICE_CURRENCY.search(cleaned) or _PRICE.search(cleaned)
    if not match:
        return None
    number = match.group(1).replace(" ", "")
    if "," in number:
        # Przecinek w polskim zapisie to zawsze część dziesiętna.
        number = number.replace(".", "").replace(",", ".")
    elif _DOT_THOUSANDS.fullmatch(number):
        number = number.replace(".", "")
    try:
        return round(float(number), 2)
    except ValueError:
        return None
