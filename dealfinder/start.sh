#!/usr/bin/env bash
# Łowca Okazji - uruchomienie lokalne (macOS / Linux).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Nie ma Pythona 3. Zainstaluj go z https://python.org i uruchom ponownie." >&2
  exit 1
fi

if [ ! -d .venv ]; then
  echo "Pierwsze uruchomienie - przygotowuję środowisko..."
  python3 -m venv .venv
  .venv/bin/pip install --quiet --upgrade pip
  .venv/bin/pip install --quiet -r requirements.txt
fi

export PYTHONPATH="$PWD/src"
exec .venv/bin/python -m dealfinder "${@:-serwer}"
