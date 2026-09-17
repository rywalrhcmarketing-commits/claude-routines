"""Lokalny serwer interfejsu. Standardowa biblioteka - zero zależności.

Nasłuchuje wyłącznie na 127.0.0.1. To narzędzie osobiste; wystawienie go na
świat oznaczałoby, że ktoś obcy odpytuje serwisy Twoim adresem IP.
"""

from __future__ import annotations

import asyncio
import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from ..config import Config, db_path
from ..engine import search
from ..models import Condition
from ..providers import all_source_names
from ..query import Query, parse_price_range
from ..storage import Store

log = logging.getLogger("dealfinder.web")
STATIC = Path(__file__).parent / "static"
HOST = "127.0.0.1"


class Handler(BaseHTTPRequestHandler):
    server_version = "LowcaOkazji"
    config: Config = Config()

    # --- routing ---

    def do_GET(self) -> None:  # noqa: N802
        path = urlsplit(self.path).path
        params = parse_qs(urlsplit(self.path).query)
        try:
            if path in ("/", "/index.html"):
                self._send_file("index.html", "text/html; charset=utf-8")
            elif path == "/api/zrodla":
                self._send_json({"zrodla": all_source_names()})
            elif path == "/api/szukaj":
                self._send_json(self._search(params))
            elif path == "/api/obserwowane":
                self._send_json({"obserwowane": self._watches()})
            elif path == "/api/sprawdz":
                self._send_json(self._check())
            else:
                self._send_json({"blad": "nie ma takiego adresu"}, status=404)
        except ValueError as exc:
            self._send_json({"blad": str(exc)}, status=400)
        except Exception as exc:  # serwer lokalny - pokaż powód, nie milcz
            log.exception("błąd obsługi %s", path)
            self._send_json({"blad": f"{type(exc).__name__}: {exc}"}, status=500)

    def do_POST(self) -> None:  # noqa: N802
        path = urlsplit(self.path).path
        try:
            body = self._read_json()
            if path == "/api/obserwuj":
                self._send_json(self._add_watch(body))
            elif path == "/api/zapomnij":
                self._send_json(self._forget(body))
            else:
                self._send_json({"blad": "nie ma takiego adresu"}, status=404)
        except ValueError as exc:
            self._send_json({"blad": str(exc)}, status=400)
        except Exception as exc:
            log.exception("błąd obsługi %s", path)
            self._send_json({"blad": f"{type(exc).__name__}: {exc}"}, status=500)

    # --- operacje ---

    def _query(self, params: dict[str, list[str]]) -> Query:
        phrase = _one(params, "q")
        if not phrase:
            raise ValueError("Brak frazy wyszukiwania")
        low, high = parse_price_range(_one(params, "cena"))
        stan = _one(params, "stan")
        sources = [s for s in (_one(params, "zrodla") or "").split(",") if s]
        return Query(
            phrase=phrase,
            min_price=low,
            max_price=high,
            condition=Condition(stan) if stan else None,
            city=_one(params, "miasto") or self.config.default_city,
            excluded=[w for w in (_one(params, "bez") or "").split(",") if w.strip()],
            sources=sources or None,
        )

    def _search(self, params: dict[str, list[str]]) -> dict:
        query = self._query(params)
        outcome = asyncio.run(search(query, self.config, keep_rejected=True))
        return {
            "fraza": query.phrase,
            "oferty": [o.to_dict() for o in outcome.offers],
            "mediana": outcome.median_price,
            "zrodla": [
                {"nazwa": s.label, "znalezione": s.found, "zostalo": s.kept, "blad": s.error}
                for s in outcome.sources
            ],
            "linki_facebook": [
                {"etykieta": link.label, "url": link.url, "podpowiedz": link.hint}
                for link in outcome.manual_links
            ],
            "odrzucone": [
                {"tytul": o.title, "powod": o.rejected_because, "url": o.url}
                for o in outcome.rejected[:50]
            ],
        }

    def _watches(self) -> list[dict]:
        with Store(db_path()) as store:
            return [
                {
                    "id": w.id,
                    "nazwa": w.name,
                    "fraza": w.query.phrase,
                    "ostatnio": w.last_run_at,
                    "historia": [dict(row) for row in store.history(w.id, limit=30)],
                }
                for w in store.list_watches()
            ]

    def _add_watch(self, body: dict) -> dict:
        params = {k: [str(v)] for k, v in body.items() if v not in (None, "")}
        query = self._query(params)
        with Store(db_path()) as store:
            watch = store.add_watch(str(body.get("nazwa") or query.phrase), query)
        return {"id": watch.id, "nazwa": watch.name}

    def _forget(self, body: dict) -> dict:
        watch_id = int(body.get("id", 0))
        with Store(db_path()) as store:
            return {"usuniete": store.remove_watch(watch_id)}

    def _check(self) -> dict:
        out = []
        with Store(db_path()) as store:
            for watch in store.list_watches():
                outcome = asyncio.run(search(watch.query, self.config))
                update = store.record(watch, outcome.offers)
                out.append(
                    {
                        "id": watch.id,
                        "nazwa": watch.name,
                        "nowe": [o.to_dict() for o in update.new_offers],
                        "przeceny": [
                            {"oferta": o.to_dict(), "poprzednia_cena": old}
                            for o, old in update.price_drops
                        ],
                        "najtansza_teraz": update.cheapest_now,
                        "najtansza_wczesniej": update.cheapest_before,
                    }
                )
        return {"wyniki": out}

    # --- pomocnicze ---

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            data = json.loads(self.rfile.read(length).decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise ValueError(f"Nieprawidłowy JSON: {exc}") from exc
        return data if isinstance(data, dict) else {}

    def _send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, name: str, content_type: str) -> None:
        path = STATIC / name
        if not path.exists():
            self._send_json({"blad": f"brak pliku {name}"}, status=404)
            return
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: object) -> None:
        log.debug(fmt, *args)


def _one(params: dict[str, list[str]], key: str) -> str | None:
    values = params.get(key)
    return values[0].strip() if values and values[0].strip() else None


def serve(config: Config, port: int) -> None:
    Handler.config = config
    with ThreadingHTTPServer((HOST, port), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nZatrzymane.")
