"""Wiersz poleceń. `python -m dealfinder --pomoc` pokazuje wszystko."""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
import webbrowser

from .config import Config, config_path, db_path, dump_dir
from .engine import SearchOutcome, search
from .models import Condition, Offer
from .providers import all_source_names
from .query import Query, parse_price_range
from .storage import Store, WatchUpdate

BOLD, DIM, GREEN, YELLOW, RED, RESET = "\033[1m", "\033[2m", "\033[32m", "\033[33m", "\033[31m", "\033[0m"


def _plain() -> bool:
    return not sys.stdout.isatty()


def paint(text: str, color: str) -> str:
    return text if _plain() else f"{color}{text}{RESET}"


def money(value: float | None) -> str:
    return "—" if value is None else f"{value:,.2f} zł".replace(",", " ")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="lowca",
        description="Łowca Okazji — szuka najtańszej oferty w kilku serwisach naraz.",
        add_help=False,
    )
    parser.add_argument("-h", "--help", "--pomoc", action="help", help="ta pomoc")
    parser.add_argument("--gadatliwy", action="store_true", help="dziennik diagnostyczny")
    sub = parser.add_subparsers(dest="command", required=True)

    find = sub.add_parser("szukaj", help="jednorazowe wyszukiwanie", add_help=False)
    find.add_argument("-h", "--help", "--pomoc", action="help", help="ta pomoc")
    find.add_argument("fraza", nargs="+", help="czego szukasz")
    find.add_argument("--cena", help="widełki, np. 500-1500, -2000, 800-")
    find.add_argument("--stan", choices=[c.value for c in Condition], help="stan przedmiotu")
    find.add_argument("--miasto", help="preferuj oferty z tego miasta")
    find.add_argument("--bez", nargs="*", default=[], help="słowa dyskwalifikujące")
    find.add_argument("--zrodla", nargs="*", help=f"z {', '.join(all_source_names())}")
    find.add_argument("--ile", type=int, default=15, help="ile ofert pokazać (domyślnie 15)")
    find.add_argument("--odrzucone", action="store_true", help="pokaż też odsiane i powód")
    find.add_argument("--zrzut", action="store_true", help="zapisz surowe odpowiedzi serwisów")

    watch = sub.add_parser("obserwuj", help="zapisz wyszukiwanie do śledzenia", add_help=False)
    watch.add_argument("-h", "--help", "--pomoc", action="help", help="ta pomoc")
    watch.add_argument("fraza", nargs="+")
    watch.add_argument("--nazwa", help="etykieta na liście")
    watch.add_argument("--cena")
    watch.add_argument("--miasto")
    watch.add_argument("--stan", choices=[c.value for c in Condition])

    sub.add_parser("obserwowane", help="lista obserwowanych wyszukiwań")

    check = sub.add_parser("sprawdz", help="przebiegnij obserwowane i pokaż zmiany")
    check.add_argument("--id", type=int, help="tylko to jedno")

    forget = sub.add_parser("zapomnij", help="usuń obserwowane wyszukiwanie")
    forget.add_argument("id", type=int)

    doctor = sub.add_parser("doktor", help="sprawdź, które źródła działają")
    doctor.add_argument("--fraza", default="rower", help="czym testować (domyślnie: rower)")
    doctor.add_argument("--zrzut", action="store_true", help="zapisz surowe odpowiedzi")

    serve = sub.add_parser("serwer", help="lokalny interfejs w przeglądarce")
    serve.add_argument("--port", type=int, default=8777)
    serve.add_argument("--bez-przegladarki", action="store_true")

    settings = sub.add_parser("ustaw", help="konfiguracja (klucze Allegro, grupy FB)")
    settings.add_argument("--allegro-id")
    settings.add_argument("--allegro-sekret")
    settings.add_argument("--grupa-fb", nargs="*", help="identyfikatory grup z adresu URL")
    settings.add_argument("--miasto")
    settings.add_argument("--opoznienie", type=float, help="przerwa między zapytaniami (s)")
    settings.add_argument("--pokaz", action="store_true", help="wypisz obecne ustawienia")

    return parser


def query_from_args(args: argparse.Namespace, config: Config) -> Query:
    low, high = parse_price_range(getattr(args, "cena", None))
    return Query(
        phrase=" ".join(args.fraza),
        min_price=low,
        max_price=high,
        condition=Condition(args.stan) if getattr(args, "stan", None) else None,
        city=getattr(args, "miasto", None) or config.default_city,
        excluded=list(getattr(args, "bez", []) or []),
        sources=getattr(args, "zrodla", None),
    )


def print_offers(outcome: SearchOutcome, limit: int, show_rejected: bool) -> None:
    if not outcome.offers:
        print(paint("Nic nie znalazłem.", YELLOW))
    else:
        cheapest = outcome.offers[0].total_price
        print()
        for index, offer in enumerate(outcome.offers[:limit], start=1):
            _print_offer(index, offer, cheapest)

    print()
    median = outcome.median_price
    if median is not None:
        print(f"{DIM if not _plain() else ''}Mediana ceny: {money(median)}"
              f"   ofert po odsianiu: {len(outcome.offers)}{RESET if not _plain() else ''}")

    for report in outcome.sources:
        if report.error:
            print(paint(f"  ✗ {report.label}: {report.error}", RED))
        else:
            print(paint(f"  ✓ {report.label}: {report.found} → {report.kept} po odsianiu", GREEN))

    if outcome.manual_links:
        print(f"\n{paint('Facebook — otwórz ręcznie', BOLD)} (automatu nie ma i nie będzie, patrz README):")
        for link in outcome.manual_links:
            print(f"  {link.label}: {link.url}")

    if show_rejected and outcome.rejected:
        print(f"\n{paint('Odsiane', BOLD)}:")
        for offer in outcome.rejected[:40]:
            print(f"  {DIM if not _plain() else ''}{offer.title[:60]:60} — {offer.rejected_because}{RESET if not _plain() else ''}")


def _print_offer(index: int, offer: Offer, cheapest: float | None) -> None:
    price = money(offer.total_price)
    if offer.total_price is not None and cheapest and offer.total_price > cheapest:
        price += paint(f"  (+{money(offer.total_price - cheapest)})", DIM)
    badge = ""
    if offer.delivery_price:
        badge += paint(f" [w tym dostawa {money(offer.delivery_price)}]", DIM)
    elif offer.delivery_price == 0.0:
        badge += paint(" [darmowa dostawa]", GREEN)
    if offer.negotiable:
        badge += paint(" [do negocjacji]", DIM)

    where = f" · {offer.location}" if offer.location else ""
    print(f"{index:2}. {paint(price, BOLD)}{badge}")
    print(f"    {offer.title[:90]}")
    print(f"    {paint(offer.source.upper() + where, DIM)} · {offer.url}")


def print_update(update: WatchUpdate) -> None:
    if not update.new_offers and not update.price_drops:
        print(f"  {paint('bez zmian', DIM)}")
        return
    for offer in update.new_offers:
        print(f"  {paint('NOWE', GREEN)} {money(offer.total_price)} — {offer.title[:70]}")
        print(f"       {offer.url}")
    for offer, old_price in update.price_drops:
        drop = old_price - (offer.total_price or 0)
        print(f"  {paint('TANIEJ', YELLOW)} {money(offer.total_price)} (było {money(old_price)}, −{money(drop)}) — {offer.title[:60]}")
        print(f"       {offer.url}")


async def cmd_search(args: argparse.Namespace, config: Config) -> int:
    query = query_from_args(args, config)
    outcome = await search(query, config, dump=args.zrzut, keep_rejected=args.odrzucone)
    print_offers(outcome, args.ile, args.odrzucone)
    return 0 if outcome.offers or not outcome.broken_sources else 1


async def cmd_watch(args: argparse.Namespace, config: Config) -> int:
    query = query_from_args(args, config)
    with Store(db_path()) as store:
        watch = store.add_watch(args.nazwa or query.phrase, query)
    print(f"Obserwuję #{watch.id}: {watch.name}")
    print("Sprawdzisz to poleceniem: lowca sprawdz")
    return 0


def cmd_list(config: Config) -> int:
    with Store(db_path()) as store:
        watches = store.list_watches()
    if not watches:
        print("Nic nie obserwujesz. Dodaj: lowca obserwuj \"iphone 15\" --cena -3000")
        return 0
    for watch in watches:
        last = watch.last_run_at[:16].replace("T", " ") if watch.last_run_at else "nigdy"
        print(f"#{watch.id:<3} {watch.name:<35} ostatnio: {last}")
    return 0


async def cmd_check(args: argparse.Namespace, config: Config) -> int:
    with Store(db_path()) as store:
        watches = [store.watch_by_id(args.id)] if args.id else store.list_watches()
        watches = [w for w in watches if w]
        if not watches:
            print("Nie ma czego sprawdzać.")
            return 1
        for watch in watches:
            print(f"\n{paint(watch.name, BOLD)} (#{watch.id})")
            outcome = await search(watch.query, config)
            update = store.record(watch, outcome.offers)
            print_update(update)
            for report in outcome.broken_sources:
                print(f"  {paint('✗ ' + report.label + ': ' + (report.error or ''), RED)}")
    return 0


def cmd_forget(args: argparse.Namespace) -> int:
    with Store(db_path()) as store:
        if store.remove_watch(args.id):
            print(f"Usunięte: #{args.id}")
            return 0
    print(f"Nie ma obserwowanego #{args.id}")
    return 1


async def cmd_doctor(args: argparse.Namespace, config: Config) -> int:
    print(f"Test każdego źródła frazą „{args.fraza}”. To potrwa kilka sekund.\n")
    query = Query(args.fraza, limit_per_source=10)
    broken = 0
    for name in all_source_names():
        query.sources = [name]
        outcome = await search(query, config, dump=args.zrzut)
        report = outcome.sources[0] if outcome.sources else None
        if report is None:
            continue
        if report.error:
            broken += 1
            print(f"{paint('✗', RED)} {report.label:<18} {report.error}")
        else:
            print(f"{paint('✓', GREEN)} {report.label:<18} {report.found} ofert, {report.kept} po odsianiu")
    print()
    if args.zrzut:
        print(f"Surowe odpowiedzi: {dump_dir()}")
    if broken:
        print(f"{broken} źródeł nie działa. Jeśli to parser, zrzut wyżej pokaże, co przyszło.")
    return 1 if broken else 0


def cmd_settings(args: argparse.Namespace, config: Config) -> int:
    if args.pokaz or not any(
        [args.allegro_id, args.allegro_sekret, args.grupa_fb is not None, args.miasto, args.opoznienie]
    ):
        secret = "ustawiony" if config.allegro_client_secret else "brak"
        print(f"Plik:        {config_path()}")
        print(f"Baza:        {db_path()}")
        print(f"Allegro ID:  {config.allegro_client_id or 'brak'}")
        print(f"Allegro sek: {secret}")
        print(f"Grupy FB:    {', '.join(config.facebook_groups) or 'brak'}")
        print(f"Źródła:      {', '.join(config.enabled_sources)}")
        print(f"Miasto:      {config.default_city or 'brak'}")
        print(f"Opóźnienie:  {config.request_delay_s}s")
        return 0

    if args.allegro_id:
        config.allegro_client_id = args.allegro_id
    if args.allegro_sekret:
        config.allegro_client_secret = args.allegro_sekret
    if args.grupa_fb is not None:
        config.facebook_groups = list(args.grupa_fb)
    if args.miasto:
        config.default_city = args.miasto
    if args.opoznienie:
        config.request_delay_s = max(0.5, args.opoznienie)
    print(f"Zapisane w {config.save()}")
    return 0


def cmd_serve(args: argparse.Namespace, config: Config) -> int:
    from .web.server import serve

    url = f"http://127.0.0.1:{args.port}/"
    print(f"Łowca Okazji działa na {url}   (Ctrl+C kończy)")
    if not args.bez_przegladarki:
        try:
            webbrowser.open(url)
        except Exception:  # przeglądarki może nie być, to nie błąd
            pass
    serve(config, args.port)
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.gadatliwy else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
    )
    config = Config.load()

    try:
        if args.command == "szukaj":
            return asyncio.run(cmd_search(args, config))
        if args.command == "obserwuj":
            return asyncio.run(cmd_watch(args, config))
        if args.command == "obserwowane":
            return cmd_list(config)
        if args.command == "sprawdz":
            return asyncio.run(cmd_check(args, config))
        if args.command == "zapomnij":
            return cmd_forget(args)
        if args.command == "doktor":
            return asyncio.run(cmd_doctor(args, config))
        if args.command == "ustaw":
            return cmd_settings(args, config)
        if args.command == "serwer":
            return cmd_serve(args, config)
    except KeyboardInterrupt:
        print("\nPrzerwane.")
        return 130
    except ValueError as exc:
        print(paint(f"Błąd: {exc}", RED), file=sys.stderr)
        return 2
    return 2
