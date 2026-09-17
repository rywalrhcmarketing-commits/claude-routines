"""Tryb pilnowania: cyklicznie przebiega obserwowane i woła, gdy coś się zmieni."""

from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass
from datetime import datetime

from .config import Config, db_path
from .engine import search
from .notify import notify, summarize
from .storage import Store, Watch, WatchUpdate

log = logging.getLogger("dealfinder.watcher")

_INTERVAL = re.compile(r"^\s*(\d+)\s*(s|sek|m|min|h|g|godz)?\s*$", re.IGNORECASE)
MIN_INTERVAL_S = 300  # pięć minut; częściej to już nękanie serwisów


@dataclass(slots=True)
class Tick:
    """Wynik jednego przebiegu - osobno, żeby dało się przetestować bez pętli."""

    at: datetime
    checked: int
    changes: list[tuple[Watch, WatchUpdate]]
    errors: list[tuple[str, str]]

    @property
    def anything_new(self) -> bool:
        return any(u.new_offers or u.price_drops for _, u in self.changes)


def parse_interval(text: str) -> int:
    """'30m' -> 1800. Bez jednostki przyjmujemy minuty."""
    match = _INTERVAL.match(text or "")
    if not match:
        raise ValueError(f"Nie rozumiem odstępu „{text}”. Napisz np. 30m, 2h albo 45.")
    value = int(match.group(1))
    unit = (match.group(2) or "m").casefold()
    seconds = value * {"s": 1, "sek": 1, "m": 60, "min": 60, "h": 3600, "g": 3600, "godz": 3600}[unit]
    if seconds < MIN_INTERVAL_S:
        raise ValueError(
            f"Za często. Minimum to {MIN_INTERVAL_S // 60} minut - serwisy też mają swoje granice."
        )
    return seconds


async def run_once(
    config: Config, *, only_id: int | None = None, quiet: bool = False
) -> Tick:
    """Jeden przebieg po obserwowanych. ``only_id`` zawęża do jednego.

    Tej samej funkcji używa jednorazowe `sprawdz` i pętla `pilnuj` - inaczej
    obie wersje rozjechałyby się przy pierwszej zmianie.
    """
    changes: list[tuple[Watch, WatchUpdate]] = []
    errors: list[tuple[str, str]] = []

    with Store(db_path()) as store:
        if only_id is not None:
            found = store.watch_by_id(only_id)
            watches = [found] if found else []
        else:
            watches = store.list_watches()
        for watch in watches:
            try:
                outcome = await search(watch.query, config)
            except Exception as exc:  # jedno zepsute nie może zatrzymać pilnowania
                log.warning("„%s”: %s", watch.name, exc)
                errors.append((watch.name, str(exc)))
                continue

            update = store.record(watch, outcome.offers)
            changes.append((watch, update))
            for report in outcome.broken_sources:
                errors.append((watch.name, f"{report.label}: {report.error}"))

            if not quiet and (update.new_offers or update.price_drops):
                title, body = summarize(
                    watch.name,
                    len(update.new_offers),
                    len(update.price_drops),
                    update.cheapest_now,
                )
                notify(title, body)

    return Tick(datetime.now(), len(watches), changes, errors)


async def run_forever(
    config: Config,
    interval_s: int,
    *,
    on_tick=None,
    max_ticks: int | None = None,
) -> None:
    """Pętla pilnowania. ``max_ticks`` istnieje po to, żeby dało się to
    przetestować bez czekania w nieskończoność."""
    ticks = 0
    while max_ticks is None or ticks < max_ticks:
        tick = await run_once(config)
        if on_tick:
            on_tick(tick)
        ticks += 1
        if max_ticks is not None and ticks >= max_ticks:
            break
        await asyncio.sleep(interval_s)
