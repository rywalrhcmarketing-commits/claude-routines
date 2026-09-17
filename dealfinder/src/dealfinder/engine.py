"""Silnik: odpytuje źródła równolegle, odsiewa, deduplikuje, układa ranking."""

from __future__ import annotations

import asyncio
import logging
import statistics
from dataclasses import dataclass, field
from pathlib import Path

from .config import Config
from .models import Offer, normalize_text
from .net import Fetcher
from .providers import build_providers
from .providers.base import Provider, ProviderResult
from .providers.facebook import ManualLink, links_for
from .query import Query
from .rank import deduplicate, score, sort_offers
from .relevance import drop_bait, judge

log = logging.getLogger("dealfinder.engine")


@dataclass(slots=True)
class SourceReport:
    source: str
    label: str
    found: int
    kept: int
    error: str | None = None


@dataclass(slots=True)
class SearchOutcome:
    query: Query
    offers: list[Offer]
    sources: list[SourceReport]
    manual_links: list[ManualLink] = field(default_factory=list)
    rejected: list[Offer] = field(default_factory=list)

    @property
    def cheapest(self) -> Offer | None:
        return self.offers[0] if self.offers else None

    @property
    def median_price(self) -> float | None:
        prices = [o.total_price for o in self.offers if o.total_price is not None]
        return round(statistics.median(prices), 2) if prices else None

    @property
    def working_sources(self) -> list[str]:
        return [s.source for s in self.sources if s.error is None]

    @property
    def broken_sources(self) -> list[SourceReport]:
        return [s for s in self.sources if s.error is not None]


async def search(
    query: Query,
    config: Config,
    *,
    dump: bool = False,
    keep_rejected: bool = False,
) -> SearchOutcome:
    providers = build_providers(config, query.sources)
    dump_path: Path | None = None
    if dump:
        from .config import dump_dir

        dump_path = dump_dir()

    async with Fetcher(delay_s=config.request_delay_s, dump_dir=dump_path) as fetcher:
        results = await asyncio.gather(
            *(provider.search(query, fetcher) for provider in providers)
        )

    return _assemble(query, config, providers, list(results), keep_rejected)


def _assemble(
    query: Query,
    config: Config,
    providers: list[Provider],
    results: list[ProviderResult],
    keep_rejected: bool,
) -> SearchOutcome:
    labels = {p.name: p.label for p in providers}
    kept: list[Offer] = []
    rejected: list[Offer] = []
    reports: list[SourceReport] = []

    for result in results:
        passed: list[Offer] = []
        for offer in result.offers:
            verdict = judge(offer, query)
            if verdict.keep:
                offer.score = verdict.match  # chwilowo dopasowanie; punkty niżej
                passed.append(offer)
            else:
                offer.rejected_because = verdict.reason
                if keep_rejected:
                    rejected.append(offer)
        kept.extend(passed)
        reports.append(
            SourceReport(
                source=result.source,
                label=labels.get(result.source, result.source),
                found=result.raw_count,
                kept=len(passed),
                error=result.error,
            )
        )

    kept, bait = drop_bait(kept)
    if keep_rejected:
        rejected.extend(bait)
    if query.city:
        kept = _prefer_city(kept, query.city)
    kept = deduplicate(kept)

    prices = [o.total_price for o in kept if o.total_price is not None and o.total_price > 0]
    cheapest = min(prices) if prices else None
    for offer in kept:
        offer.score = score(offer, query, cheapest, match=offer.score)

    return SearchOutcome(
        query=query,
        offers=sort_offers(kept),
        sources=reports,
        manual_links=links_for(query, config.facebook_groups),
        rejected=rejected,
    )


def _prefer_city(offers: list[Offer], city: str) -> list[Offer]:
    """Miasto filtrujemy u siebie: żadne z tych źródeł nie przyjmuje go tak
    samo, a odrzucanie po stronie serwisu gubiłoby oferty z wysyłką."""
    needle = normalize_text(city)
    local = [o for o in offers if o.location and needle in normalize_text(o.location)]
    shippable = [o for o in offers if o.delivery_price is not None]
    if not local:
        return offers
    seen = {o.key for o in local}
    return local + [o for o in shippable if o.key not in seen]
