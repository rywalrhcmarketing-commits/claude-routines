"""Awaryjny ekstraktor ofert z dowolnego HTML-u.

Serwisy bez API zmieniają klasy CSS co kilka miesięcy. Zamiast twardych
selektorów szukamy tego, co się nie zmienia: linku do oferty i najbliższej
liczby wyglądającej na cenę. Parser pod konkretny serwis jest dokładniejszy,
ten ma nie zwrócić zera, gdy tamten przestanie trafiać.
"""

from __future__ import annotations

import re
from urllib.parse import urljoin, urlsplit

from bs4 import BeautifulSoup, Tag

from ..models import parse_price

#: Ten sam rygor co w models._PRICE: liczba z tytułu („Level 3.0”) nie może
#: wejść do ceny stojącej obok niej w tej samej karcie.
PRICE_TEXT = re.compile(r'(?<![\d,.])\d{1,3}(?:[ .  ]?\d{3})*(?:[,.]\d{1,2})?\s*(?:zł|zl|PLN)\b', re.IGNORECASE)


def soup_of(html: str) -> BeautifulSoup:
    return BeautifulSoup(html, "html.parser")


def extract_cards(
    html: str,
    *,
    base_url: str,
    offer_path: str,
    max_cards: int = 100,
) -> list[dict[str, str | float | None]]:
    """Zwraca surowe słowniki: url, title, price, image, location.

    ``offer_path`` to fragment ścieżki charakterystyczny dla linku do oferty,
    np. ``/oferta/`` albo ``/d/oferta/``.
    """
    soup = soup_of(html)
    seen: set[str] = set()
    cards: list[dict[str, str | float | None]] = []

    for anchor in soup.find_all("a", href=True):
        href = anchor["href"]
        if offer_path not in href:
            continue
        url = urljoin(base_url, href)
        url = url.split("#")[0]
        if url in seen:
            continue

        title = _title_of(anchor)
        if not title or len(title) < 3:
            continue

        card = _card_of(anchor)
        price = _price_in(card) if card is not None else None
        seen.add(url)
        cards.append(
            {
                "url": url,
                "title": title,
                "price": price,
                "image": _image_in(card, base_url) if card is not None else None,
                "location": _location_in(card),
                "id": _id_from_url(url),
            }
        )
        if len(cards) >= max_cards:
            break
    return cards


def _title_of(anchor: Tag) -> str:
    """Tytuł bywa w atrybucie, w nagłówku w środku albo po prostu w tekście."""
    for attr in ("title", "aria-label"):
        value = anchor.get(attr)
        if value and len(value.strip()) > 3:
            return value.strip()
    heading = anchor.find(["h1", "h2", "h3", "h4", "h5", "h6"])
    if heading:
        text = heading.get_text(" ", strip=True)
        if text:
            return text
    text = anchor.get_text(" ", strip=True)
    if PRICE_TEXT.fullmatch(text.strip()):
        return ""
    return text


def _card_of(anchor: Tag, levels: int = 4) -> Tag | None:
    """Wspinaczka w górę drzewa do elementu, który wygląda na kartę oferty:
    zawiera link i cenę. Bez tego cena z sąsiedniej oferty wchodzi do tej."""
    node: Tag | None = anchor
    for _ in range(levels):
        if node is None:
            break
        parent = node.parent
        if not isinstance(parent, Tag):
            break
        if PRICE_TEXT.search(parent.get_text(" ", strip=True) or ""):
            return parent
        node = parent
    return anchor


def _price_in(card: Tag) -> float | None:
    match = PRICE_TEXT.search(card.get_text(" ", strip=True) or "")
    return parse_price(match.group(0)) if match else None


def _image_in(card: Tag, base_url: str) -> str | None:
    img = card.find("img")
    if not img:
        return None
    for attr in ("src", "data-src", "data-original"):
        value = img.get(attr)
        if value and not value.startswith("data:"):
            return urljoin(base_url, value)
    srcset = img.get("srcset")
    if srcset:
        return urljoin(base_url, srcset.split(",")[0].strip().split(" ")[0])
    return None


_LOCATION_HINT = re.compile(r"^[A-ZŁŚŻŹĆÓĄĘŃ][\w\-\. ]{2,30}(?:,\s*\w[\w\- ]{2,30})?$")


def _location_in(card: Tag) -> str | None:
    """Lokalizacja to zwykle krótki tekst z wielkiej litery, bez ceny."""
    for node in card.find_all(["span", "p", "div", "small"], limit=40):
        text = node.get_text(" ", strip=True)
        if not text or len(text) > 40 or PRICE_TEXT.search(text):
            continue
        if _LOCATION_HINT.match(text) and any(ch.isalpha() for ch in text):
            return text
    return None


def _id_from_url(url: str) -> str:
    """Identyfikator musi być unikalny w obrębie serwisu - inaczej deduplikacja
    sklei różne oferty. Slug z tytułem w roli ostatniej deski ratunku."""
    path = urlsplit(url).path.rstrip("/")
    tail = path.rsplit("/", 1)[-1]
    for pattern in (r"-ID([A-Za-z0-9]+)", r"-(\d{6,})", r"(\d{6,})"):
        match = re.search(pattern, tail)
        if match:
            return match.group(1)
    return tail or url
