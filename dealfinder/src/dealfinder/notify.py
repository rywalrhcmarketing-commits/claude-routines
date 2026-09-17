"""Powiadomienia systemowe. Bez zależności - każdy system ma swoje narzędzie.

Gdy się nie uda, nie jest to błąd: wynik i tak leci na ekran. Powiadomienie
to udogodnienie, nie jedyny kanał.
"""

from __future__ import annotations

import logging
import shutil
import subprocess
import sys

log = logging.getLogger("dealfinder.notify")

TITLE = "Łowca Okazji"
_TIMEOUT_S = 10


def notify(title: str, message: str) -> bool:
    """True, gdy powiadomienie poszło. Nigdy nie rzuca wyjątkiem."""
    try:
        if sys.platform == "darwin":
            return _macos(title, message)
        if sys.platform == "win32":
            return _windows(title, message)
        return _linux(title, message)
    except Exception as exc:  # brak narzędzia, brak sesji graficznej, cokolwiek
        log.debug("powiadomienie nie poszło: %s", exc)
        return False


def _run(args: list[str]) -> bool:
    result = subprocess.run(
        args, capture_output=True, timeout=_TIMEOUT_S, check=False
    )
    if result.returncode != 0:
        log.debug("%s -> %s", args[0], result.stderr.decode(errors="replace")[:200])
    return result.returncode == 0


def _macos(title: str, message: str) -> bool:
    script = (
        f'display notification {_applescript(message)} '
        f'with title {_applescript(TITLE)} subtitle {_applescript(title)}'
    )
    return _run(["osascript", "-e", script])


def _linux(title: str, message: str) -> bool:
    if not shutil.which("notify-send"):
        return False
    return _run(["notify-send", "-a", TITLE, f"{TITLE}: {title}", message])


def _windows(title: str, message: str) -> bool:
    """Dymek przez WinForms - jest w każdym Windowsie, bez instalowania modułów."""
    script = (
        "[reflection.assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null;"
        "$i = New-Object System.Windows.Forms.NotifyIcon;"
        "$i.Icon = [System.Drawing.SystemIcons]::Information;"
        f"$i.BalloonTipTitle = {_powershell(title)};"
        f"$i.BalloonTipText = {_powershell(message)};"
        "$i.Visible = $true; $i.ShowBalloonTip(10000); Start-Sleep -Seconds 6; $i.Dispose()"
    )
    return _run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script])


def _applescript(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _powershell(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def summarize(watch_name: str, new_count: int, drop_count: int, cheapest: float | None) -> tuple[str, str]:
    """Treść powiadomienia - krótka, bo dymek i tak utnie dłuższą."""
    parts = []
    if new_count:
        parts.append(f"{new_count} {_plural(new_count, 'nowa oferta', 'nowe oferty', 'nowych ofert')}")
    if drop_count:
        parts.append(f"{drop_count} {_plural(drop_count, 'przecena', 'przeceny', 'przecen')}")
    body = ", ".join(parts) or "bez zmian"
    if cheapest is not None:
        body += f" · od {cheapest:,.0f} zł".replace(",", " ")
    return watch_name, body


def _plural(count: int, one: str, few: str, many: str) -> str:
    """Polska odmiana: 1 oferta, 2-4 oferty, 5+ ofert."""
    if count == 1:
        return one
    last_two = count % 100
    if 12 <= last_two <= 14:
        return many
    return few if count % 10 in (2, 3, 4) else many
