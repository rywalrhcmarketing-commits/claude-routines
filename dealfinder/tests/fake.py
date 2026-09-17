"""Atrapa dostawcy - pozwala przetestować silnik bez sieci."""

from dealfinder.models import Offer
from dealfinder.providers.base import BaseProvider
from dealfinder.relevance import classify_kind, detect_condition


def offer(source, oid, title, price, **kw):
    return Offer(
        source=source,
        source_id=str(oid),
        title=title,
        url=f"https://{source}.example/{oid}",
        price=price,
        condition=kw.pop("condition", None) or detect_condition(title),
        kind=classify_kind(title),
        **kw,
    )


class FakeProvider(BaseProvider):
    def __init__(self, name, offers, error=None):
        self.name = name
        self.label = name.upper()
        self.needs_setup = False
        self._offers = offers
        self._error = error

    async def fetch(self, query, fetcher):
        if self._error:
            raise RuntimeError(self._error)
        return list(self._offers)
