"""Zapytanie użytkownika: fraza + twarde warunki, które źródła rozumieją."""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .models import Condition, normalize_text


@dataclass(slots=True)
class Query:
    phrase: str
    min_price: float | None = None
    max_price: float | None = None
    condition: Condition | None = None
    city: str | None = None
    #: słowa, które MUSZĄ wystąpić - domyślnie wszystkie znaczące z frazy
    required: list[str] = field(default_factory=list)
    #: słowa dyskwalifikujące, ponad listę wbudowaną
    excluded: list[str] = field(default_factory=list)
    limit_per_source: int = 60
    sources: list[str] | None = None

    def __post_init__(self) -> None:
        if not self.phrase or not self.phrase.strip():
            raise ValueError("Pusta fraza wyszukiwania")
        if not self.required:
            self.required = significant_words(self.phrase)
        self.excluded = [normalize_text(w) for w in self.excluded if w.strip()]
        if self.min_price is not None and self.max_price is not None:
            if self.min_price > self.max_price:
                raise ValueError("Cena minimalna jest wyższa niż maksymalna")

    @property
    def normalized_phrase(self) -> str:
        return normalize_text(self.phrase)

    def slug(self) -> str:
        """Stabilny identyfikator zapytania - klucz w bazie obserwowanych."""
        parts = [self.normalized_phrase]
        for name in ("min_price", "max_price", "city"):
            value = getattr(self, name)
            if value is not None:
                parts.append(f"{name}={value}")
        if self.condition:
            parts.append(f"condition={self.condition.value}")
        if self.excluded:
            parts.append("bez=" + ",".join(sorted(self.excluded)))
        return "|".join(parts)


#: Spójniki i przyimki - nie wymagamy ich w tytule oferty.
STOPWORDS = {
    "i", "oraz", "lub", "albo", "a", "w", "we", "z", "ze", "na", "do", "od",
    "po", "za", "o", "the", "for", "and", "with",
}


def significant_words(phrase: str) -> list[str]:
    """Słowa z frazy, które muszą się znaleźć w ofercie. Krótkie tokeny
    zostawiamy tylko, gdy niosą treść (np. '13' w 'iPhone 13')."""
    words = []
    for word in normalize_text(phrase).split():
        if word in STOPWORDS:
            continue
        if len(word) == 1 and not word.isdigit():
            continue
        words.append(word)
    return words


_RANGE = re.compile(r"^\s*(\d+(?:[.,]\d+)?)\s*-\s*(\d+(?:[.,]\d+)?)\s*$")


def parse_price_range(text: str | None) -> tuple[float | None, float | None]:
    """'500-1500' -> (500, 1500); '-1500' -> (None, 1500); '500-' -> (500, None)."""
    if not text or not text.strip():
        return (None, None)
    raw = text.strip()
    match = _RANGE.match(raw)
    if match:
        return (float(match.group(1).replace(",", ".")), float(match.group(2).replace(",", ".")))
    if raw.startswith("-"):
        return (None, float(raw[1:].replace(",", ".")))
    if raw.endswith("-"):
        return (float(raw[:-1].replace(",", ".")), None)
    value = float(raw.replace(",", "."))
    return (None, value)
