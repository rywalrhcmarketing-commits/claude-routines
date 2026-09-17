import pytest

from dealfinder.config import Config
from dealfinder.engine import _assemble
from dealfinder.models import Condition
from dealfinder.query import Query
from fake import FakeProvider, offer


async def run(providers, query, keep_rejected=False):
    results = [await p.search(query, None) for p in providers]
    return _assemble(query, Config(), providers, results, keep_rejected)


@pytest.mark.asyncio
async def test_ranks_by_total_price_not_headline_price():
    olx = FakeProvider("olx", [offer("olx", 1, "iPhone 15 128GB", 2700.0)])
    allegro = FakeProvider(
        "allegro",
        [offer("allegro", 2, "iPhone 15 128GB czarny", 2650.0, delivery_price=120.0)],
    )
    out = await run([olx, allegro], Query("iphone 15"))
    assert [o.source for o in out.offers] == ["olx", "allegro"]
    assert out.cheapest.total_price == 2700.0


@pytest.mark.asyncio
async def test_drops_accessories_and_wanted_ads():
    p = FakeProvider(
        "olx",
        [
            offer("olx", 1, "iPhone 15 128GB", 2700.0),
            offer("olx", 2, "Etui do iPhone 15 silikonowe", 29.0),
            offer("olx", 3, "Kupię iPhone 15 każdy stan", None),
            offer("olx", 4, "Szkło hartowane iPhone 15", 15.0),
        ],
    )
    out = await run([p], Query("iphone 15"), keep_rejected=True)
    assert [o.source_id for o in out.offers] == ["1"]
    reasons = {o.source_id: o.rejected_because for o in out.rejected}
    assert "akcesorium" in reasons["2"]
    assert "kupie" in reasons["3"]


@pytest.mark.asyncio
async def test_accessory_kept_when_user_wants_it():
    p = FakeProvider("olx", [offer("olx", 2, "Etui do iPhone 15 silikonowe", 29.0)])
    out = await run([p], Query("etui iphone 15"))
    assert len(out.offers) == 1


@pytest.mark.asyncio
async def test_deduplicates_same_offer_across_sources():
    a = FakeProvider("olx", [offer("olx", 1, "Rower górski Kross Level 3.0", 1800.0)])
    b = FakeProvider(
        "allegrolokalnie",
        [offer("allegrolokalnie", 9, "Rower gorski Kross Level 3.0", 1810.0)],
    )
    out = await run([a, b], Query("rower kross level"))
    assert len(out.offers) == 1
    assert out.offers[0].source == "olx"  # tańszy egzemplarz


@pytest.mark.asyncio
async def test_bait_price_dropped_against_median():
    offers = [offer("olx", i, "PlayStation 5 slim", p) for i, p in
              enumerate([1900.0, 1950.0, 2000.0, 2100.0, 2050.0, 1.0], start=1)]
    out = await run([FakeProvider("olx", offers)], Query("playstation 5"))
    assert 1.0 not in [o.total_price for o in out.offers]
    assert len(out.offers) == 5


@pytest.mark.asyncio
async def test_price_range_filter():
    offers = [offer("olx", i, "Laptop Dell Latitude", p) for i, p in
              enumerate([800.0, 1500.0, 3000.0], start=1)]
    out = await run([FakeProvider("olx", offers)], Query("laptop dell latitude", min_price=1000, max_price=2000))
    assert [o.total_price for o in out.offers] == [1500.0]


@pytest.mark.asyncio
async def test_broken_source_does_not_kill_search():
    good = FakeProvider("olx", [offer("olx", 1, "Rower Kross Level", 1800.0)])
    bad = FakeProvider("vinted", [], error="HTTP 403")
    out = await run([good, bad], Query("rower kross level"))
    assert len(out.offers) == 1
    assert [s.source for s in out.broken_sources] == ["vinted"]


@pytest.mark.asyncio
async def test_damaged_rejected_when_new_requested():
    offers = [
        offer("olx", 1, "iPhone 15 128GB nowy zafoliowany", 3400.0),
        offer("olx", 2, "iPhone 15 128GB uszkodzony zbita szybka", 1200.0),
    ]
    out = await run([FakeProvider("olx", offers)], Query("iphone 15", condition=Condition.NEW))
    assert [o.source_id for o in out.offers] == ["1"]


@pytest.mark.asyncio
async def test_przyneta_trafia_na_liste_odrzuconych_z_powodem():
    offers = [offer("olx", i, "PlayStation 5 slim", p) for i, p in
              enumerate([1900.0, 1950.0, 2000.0, 2100.0, 2050.0, 1.0], start=1)]
    out = await run([FakeProvider("olx", offers)], Query("playstation 5"), keep_rejected=True)
    przyneta = [o for o in out.rejected if o.source_id == "6"]
    assert len(przyneta) == 1
    assert "mediany" in przyneta[0].rejected_because
