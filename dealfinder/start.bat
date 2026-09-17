@echo off
REM Lowca Okazji - uruchomienie lokalne (Windows).
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo Nie ma Pythona. Zainstaluj go z https://python.org - zaznacz "Add Python to PATH".
  pause
  exit /b 1
)

if not exist .venv (
  echo Pierwsze uruchomienie - przygotowuje srodowisko...
  python -m venv .venv
  .venv\Scripts\pip install --quiet --upgrade pip
  .venv\Scripts\pip install --quiet -r requirements.txt
)

set PYTHONPATH=%CD%\src
if "%~1"=="" (
  .venv\Scripts\python -m dealfinder serwer
) else (
  .venv\Scripts\python -m dealfinder %*
)
pause
