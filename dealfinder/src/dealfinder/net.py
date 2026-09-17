"""Jeden klient HTTP dla wszystkich źródeł: limit tempa, powtórki, dziennik."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections import defaultdict
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import httpx

from . import __version__

log = logging.getLogger("dealfinder.net")

USER_AGENT = (
    f"LowcaOkazji/{__version__} (osobista wyszukiwarka ofert; "
    "kontakt przez repozytorium)"
)

#: Minimalna przerwa między zapytaniami do TEGO SAMEGO hosta.
DEFAULT_DELAY_S = 1.5
DEFAULT_TIMEOUT_S = 20.0
RETRY_STATUS = {429, 500, 502, 503, 504}


class Blocked(Exception):
    """Serwis odmówił (403/429 po powtórkach). Nie obchodzimy tego."""


class HostLimiter:
    """Bramka per host - równoległość między serwisami, kolejka wewnątrz."""

    def __init__(self, delay_s: float = DEFAULT_DELAY_S) -> None:
        self.delay_s = delay_s
        self._locks: dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
        self._last: dict[str, float] = {}

    async def wait(self, host: str) -> None:
        async with self._locks[host]:
            previous = self._last.get(host)
            if previous is not None:
                gap = self.delay_s - (time.monotonic() - previous)
                if gap > 0:
                    await asyncio.sleep(gap)
            self._last[host] = time.monotonic()


class Fetcher:
    """Opakowanie na httpx. Każdy dostawca dostaje ten sam obiekt."""

    def __init__(
        self,
        *,
        delay_s: float = DEFAULT_DELAY_S,
        timeout_s: float = DEFAULT_TIMEOUT_S,
        retries: int = 2,
        dump_dir: Path | None = None,
    ) -> None:
        self.limiter = HostLimiter(delay_s)
        self.retries = retries
        self.dump_dir = dump_dir
        self._client = httpx.AsyncClient(
            timeout=timeout_s,
            follow_redirects=True,
            headers={
                "User-Agent": USER_AGENT,
                "Accept-Language": "pl-PL,pl;q=0.9",
            },
        )

    async def __aenter__(self) -> Fetcher:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    async def close(self) -> None:
        await self._client.aclose()

    async def get(
        self,
        url: str,
        *,
        params: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
        label: str | None = None,
    ) -> httpx.Response:
        host = urlsplit(url).netloc
        last_error: Exception | None = None
        for attempt in range(self.retries + 1):
            await self.limiter.wait(host)
            try:
                response = await self._client.get(url, params=params, headers=headers)
            except httpx.HTTPError as exc:
                last_error = exc
                log.debug("%s: błąd sieci (%s), próba %d", host, exc, attempt + 1)
            else:
                if response.status_code in RETRY_STATUS and attempt < self.retries:
                    wait_s = _retry_after(response) or (2.0 ** attempt)
                    log.debug("%s: HTTP %d, czekam %.1fs", host, response.status_code, wait_s)
                    await asyncio.sleep(wait_s)
                    continue
                if response.status_code in (403, 429):
                    self._dump(label or host, response.text)
                    raise Blocked(f"{host} odmówił dostępu (HTTP {response.status_code})")
                response.raise_for_status()
                self._dump(label or host, response.text)
                return response
        if last_error is not None:
            raise Blocked(f"{host}: {_reason(last_error)}") from last_error
        raise Blocked(f"{host}: brak odpowiedzi")

    async def post(
        self,
        url: str,
        *,
        data: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
        auth: tuple[str, str] | None = None,
    ) -> httpx.Response:
        await self.limiter.wait(urlsplit(url).netloc)
        response = await self._client.post(
            url, data=data, json=json_body, headers=headers, auth=auth
        )
        response.raise_for_status()
        return response

    def _dump(self, label: str, body: str) -> None:
        """Surowa odpowiedź na dysk - materiał do naprawy parsera."""
        if not self.dump_dir:
            return
        self.dump_dir.mkdir(parents=True, exist_ok=True)
        safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in label)
        path = self.dump_dir / f"{safe}-{int(time.time())}.txt"
        path.write_text(body[:2_000_000], encoding="utf-8", errors="replace")
        log.info("Zrzut odpowiedzi: %s", path)


def _reason(error: Exception) -> str:
    """Komunikaty httpx bywają puste - lepiej pokazać typ niż nic."""
    message = str(error).strip()
    if isinstance(error, httpx.ConnectTimeout):
        return "przekroczony czas połączenia"
    if isinstance(error, httpx.ReadTimeout):
        return "serwis nie odpowiedział na czas"
    if isinstance(error, httpx.ProxyError):
        return f"połączenie odrzucone przez proxy ({message or 'bez powodu'})"
    if isinstance(error, httpx.ConnectError):
        return f"brak połączenia ({message or 'sprawdź internet'})"
    return message or type(error).__name__


def _retry_after(response: httpx.Response) -> float | None:
    value = response.headers.get("Retry-After")
    if not value:
        return None
    try:
        return min(float(value), 30.0)
    except ValueError:
        return None


def load_json(response: httpx.Response) -> Any:
    """json() httpx-a wybucha brzydko, gdy serwis zwróci HTML zamiast JSON-a."""
    try:
        return response.json()
    except (json.JSONDecodeError, ValueError) as exc:
        snippet = response.text[:200].replace("\n", " ")
        raise ValueError(
            f"{response.url.host} zwrócił coś, co nie jest JSON-em: {snippet!r}"
        ) from exc
