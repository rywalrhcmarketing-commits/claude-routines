"""Kontrakt dostawcy. Jeden serwis = jedna klasa = jeden plik."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Protocol

from ..models import Offer
from ..net import Fetcher
from ..query import Query

log = logging.getLogger("dealfinder.provider")


@dataclass(slots=True)
class ProviderResult:
    source: str
    offers: list[Offer]
    error: str | None = None
    #: Ile ofert serwis zwrócił, zanim cokolwiek odsialiśmy.
    raw_count: int = 0

    @property
    def ok(self) -> bool:
        return self.error is None


class Provider(Protocol):
    name: str
    label: str
    #: Czy działa bez konfiguracji (klucze API itd.).
    needs_setup: bool

    async def search(self, query: Query, fetcher: Fetcher) -> ProviderResult: ...


#: Ile czasu dajemy jednemu źródłu. Powyżej tego reszta wyników jest ważniejsza
#: niż czekanie - i tak każde źródło ma własne limity na pojedyncze zapytanie.
PROVIDER_BUDGET_S = 45.0


class BaseProvider:
    name = "base"
    label = "Base"
    needs_setup = False

    async def search(self, query: Query, fetcher: Fetcher) -> ProviderResult:
        try:
            offers = await asyncio.wait_for(
                self.fetch(query, fetcher), timeout=PROVIDER_BUDGET_S
            )
        except TimeoutError:
            log.debug("%s: przekroczony budżet czasu", self.name)
            return ProviderResult(
                self.name,
                [],
                error=f"nie odpowiedział w {PROVIDER_BUDGET_S:.0f} s - pominięty",
            )
        except Exception as exc:  # dostawca nie może wywrócić całego wyszukiwania
            # Powód i tak trafia do raportu źródła - tu tylko ślad dla --gadatliwy.
            log.debug("%s: %s", self.name, exc)
            return ProviderResult(self.name, [], error=str(exc))
        return ProviderResult(self.name, offers, raw_count=len(offers))

    async def fetch(self, query: Query, fetcher: Fetcher) -> list[Offer]:
        raise NotImplementedError
