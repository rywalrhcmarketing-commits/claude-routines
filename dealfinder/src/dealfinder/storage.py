"""Baza lokalna: obserwowane wyszukiwania, widziane oferty, historia cen.

SQLite, jeden plik w katalogu domowym. Nic nie wychodzi na zewnątrz.
"""

from __future__ import annotations

import json
import sqlite3
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, UTC
from pathlib import Path

from .models import Offer
from .query import Query

SCHEMA = """
CREATE TABLE IF NOT EXISTS watches (
    id           INTEGER PRIMARY KEY,
    slug         TEXT NOT NULL UNIQUE,
    name         TEXT NOT NULL,
    query_json   TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    last_run_at  TEXT
);

CREATE TABLE IF NOT EXISTS seen_offers (
    watch_id     INTEGER NOT NULL REFERENCES watches(id) ON DELETE CASCADE,
    offer_key    TEXT NOT NULL,
    title        TEXT NOT NULL,
    url          TEXT NOT NULL,
    first_price  REAL,
    last_price   REAL,
    first_seen   TEXT NOT NULL,
    last_seen    TEXT NOT NULL,
    PRIMARY KEY (watch_id, offer_key)
);

CREATE TABLE IF NOT EXISTS price_history (
    watch_id     INTEGER NOT NULL REFERENCES watches(id) ON DELETE CASCADE,
    checked_at   TEXT NOT NULL,
    cheapest     REAL,
    median       REAL,
    offer_count  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_history_watch ON price_history(watch_id, checked_at);
"""


@dataclass(slots=True)
class Watch:
    id: int
    slug: str
    name: str
    query: Query
    created_at: str
    last_run_at: str | None


@dataclass(slots=True)
class WatchUpdate:
    """Co się zmieniło od poprzedniego sprawdzenia."""

    new_offers: list[Offer]
    price_drops: list[tuple[Offer, float]]  # (oferta, poprzednia cena)
    cheapest_now: float | None
    cheapest_before: float | None


def _now() -> str:
    return datetime.now(UTC).isoformat()


class Store:
    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, timeout=10.0)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON")
        # `pilnuj` w terminalu i przeglądarka chodzą naraz i obie piszą.
        self._conn.execute("PRAGMA journal_mode = WAL")
        self._conn.execute("PRAGMA busy_timeout = 10000")
        with closing(self._conn.cursor()) as cur:
            cur.executescript(SCHEMA)
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    def __enter__(self) -> Store:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # --- obserwowane wyszukiwania ---

    def add_watch(self, name: str, query: Query) -> Watch:
        slug = query.slug()
        payload = json.dumps(_query_to_dict(query), ensure_ascii=False)
        with self._conn:
            self._conn.execute(
                "INSERT INTO watches (slug, name, query_json, created_at) VALUES (?,?,?,?) "
                "ON CONFLICT(slug) DO UPDATE SET name=excluded.name, query_json=excluded.query_json",
                (slug, name, payload, _now()),
            )
        found = self.watch_by_slug(slug)
        assert found is not None
        return found

    def watch_by_slug(self, slug: str) -> Watch | None:
        row = self._conn.execute("SELECT * FROM watches WHERE slug = ?", (slug,)).fetchone()
        return _row_to_watch(row) if row else None

    def watch_by_id(self, watch_id: int) -> Watch | None:
        row = self._conn.execute("SELECT * FROM watches WHERE id = ?", (watch_id,)).fetchone()
        return _row_to_watch(row) if row else None

    def list_watches(self) -> list[Watch]:
        rows = self._conn.execute("SELECT * FROM watches ORDER BY created_at").fetchall()
        return [_row_to_watch(row) for row in rows]

    def remove_watch(self, watch_id: int) -> bool:
        with self._conn:
            cur = self._conn.execute("DELETE FROM watches WHERE id = ?", (watch_id,))
        return cur.rowcount > 0

    # --- zapis wyników i wykrywanie zmian ---

    def record(self, watch: Watch, offers: list[Offer]) -> WatchUpdate:
        """Zapisuje przebieg i mówi, co nowego. Wywołanie zmienia stan bazy -
        drugie wywołanie z tymi samymi ofertami nie zgłosi już nic nowego."""
        now = _now()
        known = {
            row["offer_key"]: row
            for row in self._conn.execute(
                "SELECT * FROM seen_offers WHERE watch_id = ?", (watch.id,)
            )
        }
        previous_cheapest = self._last_cheapest(watch.id)

        new_offers: list[Offer] = []
        price_drops: list[tuple[Offer, float]] = []

        with self._conn:
            for offer in offers:
                price = offer.total_price
                row = known.get(offer.key)
                if row is None:
                    new_offers.append(offer)
                    self._conn.execute(
                        "INSERT INTO seen_offers (watch_id, offer_key, title, url,"
                        " first_price, last_price, first_seen, last_seen)"
                        " VALUES (?,?,?,?,?,?,?,?)",
                        (watch.id, offer.key, offer.title, offer.url, price, price, now, now),
                    )
                    continue

                old_price = row["last_price"]
                if price is not None and old_price is not None and price < old_price - 0.005:
                    price_drops.append((offer, float(old_price)))
                self._conn.execute(
                    "UPDATE seen_offers SET last_price = ?, last_seen = ?, title = ?, url = ?"
                    " WHERE watch_id = ? AND offer_key = ?",
                    (price, now, offer.title, offer.url, watch.id, offer.key),
                )

            prices = [o.total_price for o in offers if o.total_price is not None]
            cheapest = min(prices) if prices else None
            median = _median(prices)
            self._conn.execute(
                "INSERT INTO price_history (watch_id, checked_at, cheapest, median, offer_count)"
                " VALUES (?,?,?,?,?)",
                (watch.id, now, cheapest, median, len(offers)),
            )
            self._conn.execute(
                "UPDATE watches SET last_run_at = ? WHERE id = ?", (now, watch.id)
            )

        return WatchUpdate(new_offers, price_drops, cheapest, previous_cheapest)

    def history(self, watch_id: int, limit: int = 60) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                "SELECT checked_at, cheapest, median, offer_count FROM price_history"
                " WHERE watch_id = ? ORDER BY checked_at DESC LIMIT ?",
                (watch_id, limit),
            )
        )

    def _last_cheapest(self, watch_id: int) -> float | None:
        row = self._conn.execute(
            "SELECT cheapest FROM price_history WHERE watch_id = ?"
            " ORDER BY checked_at DESC LIMIT 1",
            (watch_id,),
        ).fetchone()
        return float(row["cheapest"]) if row and row["cheapest"] is not None else None


def _median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2


def _query_to_dict(query: Query) -> dict:
    return {
        "phrase": query.phrase,
        "min_price": query.min_price,
        "max_price": query.max_price,
        "condition": query.condition.value if query.condition else None,
        "city": query.city,
        "excluded": query.excluded,
        "limit_per_source": query.limit_per_source,
        "sources": query.sources,
    }


def _query_from_dict(data: dict) -> Query:
    from .models import Condition

    condition = data.get("condition")
    return Query(
        phrase=data["phrase"],
        min_price=data.get("min_price"),
        max_price=data.get("max_price"),
        condition=Condition(condition) if condition else None,
        city=data.get("city"),
        excluded=data.get("excluded") or [],
        limit_per_source=data.get("limit_per_source", 60),
        sources=data.get("sources"),
    )


def _row_to_watch(row: sqlite3.Row) -> Watch:
    return Watch(
        id=int(row["id"]),
        slug=row["slug"],
        name=row["name"],
        query=_query_from_dict(json.loads(row["query_json"])),
        created_at=row["created_at"],
        last_run_at=row["last_run_at"],
    )
