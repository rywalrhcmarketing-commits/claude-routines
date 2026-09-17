"""Tryb pilnowania - bez czekania w realnym czasie, dzięki --ile-razy."""

import pytest

from dealfinder import watcher
from dealfinder.config import Config
from dealfinder.engine import SearchOutcome, SourceReport
from dealfinder.query import Query
from dealfinder.storage import Store
from fake import offer


@pytest.fixture
def dom(tmp_path, monkeypatch):
    monkeypatch.setenv("LOWCA_HOME", str(tmp_path))
    return tmp_path


def podstaw_wyniki(monkeypatch, serie):
    """Kolejne przebiegi dostają kolejne listy ofert z ``serie``."""
    kolejka = list(serie)

    async def fake_search(query, config, **kw):
        offers = kolejka.pop(0) if kolejka else []
        return SearchOutcome(query=query, offers=offers, sources=[SourceReport("olx", "OLX", len(offers), len(offers))])

    monkeypatch.setattr(watcher, "search", fake_search)
    wyslane = []
    monkeypatch.setattr(watcher, "notify", lambda t, b: wyslane.append((t, b)) or True)
    return wyslane


@pytest.mark.asyncio
async def test_odstepy_ponizej_minimum_odrzucone():
    assert watcher.parse_interval("30m") == 1800
    assert watcher.parse_interval("2h") == 7200
    assert watcher.parse_interval("45") == 2700
    with pytest.raises(ValueError, match="Za często"):
        watcher.parse_interval("30s")
    with pytest.raises(ValueError, match="Nie rozumiem"):
        watcher.parse_interval("kiedyś")


@pytest.mark.asyncio
async def test_pierwszy_przebieg_zglasza_wszystko_drugi_nic(dom, monkeypatch):
    oferty = [offer("olx", 1, "Rower Kross Level", 1800.0)]
    wyslane = podstaw_wyniki(monkeypatch, [oferty, list(oferty)])

    with Store(watcher.db_path()) as store:
        store.add_watch("Rower", Query("rower kross level"))

    pierwszy = await watcher.run_once(Config())
    assert pierwszy.checked == 1
    assert len(pierwszy.changes[0][1].new_offers) == 1
    assert pierwszy.anything_new is True
    assert len(wyslane) == 1

    drugi = await watcher.run_once(Config())
    assert drugi.anything_new is False
    assert len(wyslane) == 1  # bez zmian = bez powiadomienia


@pytest.mark.asyncio
async def test_przecena_woła_powiadomieniem(dom, monkeypatch):
    wyslane = podstaw_wyniki(
        monkeypatch,
        [
            [offer("olx", 1, "Rower Kross Level", 1800.0)],
            [offer("olx", 1, "Rower Kross Level", 1500.0)],
        ],
    )
    with Store(watcher.db_path()) as store:
        store.add_watch("Rower", Query("rower kross level"))

    await watcher.run_once(Config())
    tick = await watcher.run_once(Config())

    _, update = tick.changes[0]
    assert len(update.price_drops) == 1
    assert update.price_drops[0][1] == 1800.0
    assert "przecena" in wyslane[-1][1]


@pytest.mark.asyncio
async def test_quiet_nie_wysyla_powiadomien(dom, monkeypatch):
    wyslane = podstaw_wyniki(monkeypatch, [[offer("olx", 1, "Rower Kross", 900.0)]])
    with Store(watcher.db_path()) as store:
        store.add_watch("Rower", Query("rower kross"))
    await watcher.run_once(Config(), quiet=True)
    assert wyslane == []


@pytest.mark.asyncio
async def test_zepsute_wyszukiwanie_nie_zatrzymuje_pozostalych(dom, monkeypatch):
    async def fake_search(query, config, **kw):
        if "psuj" in query.phrase:
            raise RuntimeError("serwis padł")
        return SearchOutcome(query=query, offers=[offer("olx", 1, "Rower Kross", 900.0)], sources=[])

    monkeypatch.setattr(watcher, "search", fake_search)
    monkeypatch.setattr(watcher, "notify", lambda t, b: True)

    with Store(watcher.db_path()) as store:
        store.add_watch("Psuj", Query("psuj to"))
        store.add_watch("Rower", Query("rower kross"))

    tick = await watcher.run_once(Config())
    assert tick.checked == 2
    assert [w.name for w, _ in tick.changes] == ["Rower"]
    assert tick.errors == [("Psuj", "serwis padł")]


@pytest.mark.asyncio
async def test_only_id_zaweza_do_jednego(dom, monkeypatch):
    podstaw_wyniki(monkeypatch, [[offer("olx", 1, "Rower", 900.0)]])
    with Store(watcher.db_path()) as store:
        a = store.add_watch("A", Query("rower kross"))
        store.add_watch("B", Query("laptop dell"))
    tick = await watcher.run_once(Config(), only_id=a.id)
    assert tick.checked == 1 and tick.changes[0][0].name == "A"


@pytest.mark.asyncio
async def test_petla_konczy_sie_po_zadanej_liczbie_przebiegow(dom, monkeypatch):
    podstaw_wyniki(monkeypatch, [[], [], []])
    with Store(watcher.db_path()) as store:
        store.add_watch("Rower", Query("rower kross"))

    przebiegi = []
    # Odstęp 0 s tylko tutaj - parse_interval i tak nie wpuści mniej niż 5 minut.
    await watcher.run_forever(Config(), 0, on_tick=przebiegi.append, max_ticks=3)
    assert len(przebiegi) == 3
