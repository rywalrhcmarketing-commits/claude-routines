from pathlib import Path

from dealfinder.query import Query
from dealfinder.storage import Store
from fake import offer


def test_detects_new_offers_and_price_drops(tmp_path: Path):
    with Store(tmp_path / "db.sqlite3") as store:
        watch = store.add_watch("Rower", Query("rower kross level", max_price=2500))

        first = store.record(watch, [offer("olx", 1, "Rower Kross Level", 1800.0)])
        assert len(first.new_offers) == 1
        assert first.cheapest_before is None

        again = store.record(watch, [offer("olx", 1, "Rower Kross Level", 1800.0)])
        assert again.new_offers == []
        assert again.price_drops == []

        third = store.record(
            watch,
            [
                offer("olx", 1, "Rower Kross Level", 1650.0),
                offer("olx", 2, "Rower Kross Level 3.0", 1900.0),
            ],
        )
        assert [o.source_id for o in third.new_offers] == ["2"]
        assert len(third.price_drops) == 1
        dropped, old_price = third.price_drops[0]
        assert dropped.source_id == "1" and old_price == 1800.0
        assert third.cheapest_now == 1650.0 and third.cheapest_before == 1800.0


def test_watch_survives_roundtrip(tmp_path: Path):
    db = tmp_path / "db.sqlite3"
    with Store(db) as store:
        store.add_watch("Laptop", Query("laptop dell", min_price=500, max_price=2000, city="Kraków"))
    with Store(db) as store:
        watches = store.list_watches()
        assert len(watches) == 1
        q = watches[0].query
        assert (q.phrase, q.min_price, q.max_price, q.city) == ("laptop dell", 500, 2000, "Kraków")


def test_same_query_is_not_duplicated(tmp_path: Path):
    with Store(tmp_path / "db.sqlite3") as store:
        a = store.add_watch("A", Query("iphone 15"))
        b = store.add_watch("B", Query("iphone 15"))
        assert a.id == b.id
        assert len(store.list_watches()) == 1


def test_history_is_recorded(tmp_path: Path):
    with Store(tmp_path / "db.sqlite3") as store:
        watch = store.add_watch("PS5", Query("playstation 5"))
        store.record(watch, [offer("olx", 1, "PlayStation 5", 1900.0), offer("olx", 2, "PlayStation 5 slim", 2100.0)])
        rows = store.history(watch.id)
        assert len(rows) == 1
        assert rows[0]["cheapest"] == 1900.0 and rows[0]["median"] == 2000.0 and rows[0]["offer_count"] == 2


def test_remove_watch_cascades(tmp_path: Path):
    with Store(tmp_path / "db.sqlite3") as store:
        watch = store.add_watch("X", Query("rower kross"))
        store.record(watch, [offer("olx", 1, "Rower Kross", 900.0)])
        assert store.remove_watch(watch.id) is True
        assert store.list_watches() == []
        assert store.history(watch.id) == []
