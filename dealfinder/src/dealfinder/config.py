"""Konfiguracja lokalna - jeden plik JSON w katalogu domowym użytkownika."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

APP_DIR_ENV = "LOWCA_HOME"
DEFAULT_DIR = Path.home() / ".lowca-okazji"


def app_dir() -> Path:
    return Path(os.environ.get(APP_DIR_ENV) or DEFAULT_DIR)


def config_path() -> Path:
    return app_dir() / "config.json"


def db_path() -> Path:
    return app_dir() / "oferty.sqlite3"


def dump_dir() -> Path:
    return app_dir() / "zrzuty"


@dataclass(slots=True)
class Config:
    allegro_client_id: str | None = None
    allegro_client_secret: str | None = None
    #: Identyfikatory grup z facebook.com/groups/<TO>/ - do budowania linków.
    facebook_groups: list[str] = field(default_factory=list)
    #: Źródła włączone domyślnie.
    enabled_sources: list[str] = field(
        default_factory=lambda: ["olx", "allegro", "allegrolokalnie", "vinted"]
    )
    #: Przerwa między zapytaniami do jednego serwisu (sekundy).
    request_delay_s: float = 1.5
    default_city: str | None = None

    @classmethod
    def load(cls, path: Path | None = None) -> Config:
        target = path or config_path()
        if not target.exists():
            return cls()
        try:
            data = json.loads(target.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise SystemExit(f"Zepsuty plik konfiguracji {target}: {exc}") from exc
        known = {f for f in cls.__dataclass_fields__}
        return cls(**{k: v for k, v in data.items() if k in known})

    def save(self, path: Path | None = None) -> Path:
        target = path or config_path()
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "allegro_client_id": self.allegro_client_id,
            "allegro_client_secret": self.allegro_client_secret,
            "facebook_groups": self.facebook_groups,
            "enabled_sources": self.enabled_sources,
            "request_delay_s": self.request_delay_s,
            "default_city": self.default_city,
        }
        target.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        try:
            target.chmod(0o600)  # w środku jest sekret Allegro
        except OSError:
            pass
        return target
