"""Rejestr dostawców - jedyne miejsce, które wie, co jest dostępne."""

from __future__ import annotations

from ..config import Config
from .allegro import AllegroProvider
from .base import BaseProvider, Provider, ProviderResult
from .generic_html import ALLEGRO_LOKALNIE, SPRZEDAJEMY, GenericHtmlProvider, SiteSpec
from .olx import OlxProvider
from .vinted import VintedProvider

__all__ = [
    "AllegroProvider",
    "BaseProvider",
    "GenericHtmlProvider",
    "OlxProvider",
    "Provider",
    "ProviderResult",
    "SiteSpec",
    "VintedProvider",
    "build_providers",
    "all_source_names",
]

_HTML_SITES = {spec.name: spec for spec in (ALLEGRO_LOKALNIE, SPRZEDAJEMY)}


def all_source_names() -> list[str]:
    return ["olx", "allegro", *_HTML_SITES, "vinted"]


def build_providers(config: Config, only: list[str] | None = None) -> list[Provider]:
    """Buduje dostawców wskazanych w ``only``, a gdy go nie ma - włączonych
    w konfiguracji. Nieznane nazwy zgłaszamy, zamiast po cichu pomijać."""
    wanted = [name.strip().casefold() for name in (only or config.enabled_sources) if name.strip()]
    unknown = [name for name in wanted if name not in all_source_names()]
    if unknown:
        raise ValueError(
            f"Nieznane źródła: {', '.join(unknown)}. Dostępne: {', '.join(all_source_names())}"
        )

    providers: list[Provider] = []
    for name in wanted:
        if name == "olx":
            providers.append(OlxProvider())
        elif name == "allegro":
            providers.append(
                AllegroProvider(config.allegro_client_id, config.allegro_client_secret)
            )
        elif name == "vinted":
            providers.append(VintedProvider())
        elif name in _HTML_SITES:
            providers.append(GenericHtmlProvider(_HTML_SITES[name]))
    return providers
