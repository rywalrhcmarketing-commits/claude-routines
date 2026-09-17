"""Odsiewanie - przypadki wyłapane na danych pokazowych i w praktyce."""

import pytest

from dealfinder.models import normalize_text
from dealfinder.query import Query
from dealfinder.relevance import drop_bait, judge
from fake import offer


def ocena(tytul, fraza, **kw):
    return judge(offer("olx", 1, tytul, kw.pop("cena", 1000.0)), Query(fraza, **kw))


class TestJednostki:
    """'128 GB' i '128GB' to ta sama pojemność - ludzie piszą raz tak, raz tak."""

    def test_spacja_przed_jednostka_nie_psuje_dopasowania(self):
        assert ocena("iPhone 15 128 GB stan idealny", "iphone 15 128gb").keep
        assert ocena("iPhone 15 128GB", "iphone 15 128 gb").keep

    def test_rozne_jednostki(self):
        assert normalize_text("dysk 2 TB") == "dysk 2tb"
        assert normalize_text("rower koła 26 cali") == "rower kola 26cali"
        assert normalize_text("bateria 5000 mAh") == "bateria 5000mah"

    def test_inna_pojemnosc_nadal_odpada(self):
        werdykt = ocena("iPhone 15 256 GB", "iphone 15 128gb")
        assert not werdykt.keep and "128gb" in werdykt.reason


class TestAkcesoria:
    def test_etui_odpada_gdy_szukamy_telefonu(self):
        werdykt = ocena("Etui do iPhone 15 silikonowe", "iphone 15")
        assert not werdykt.keep and "akcesorium" in werdykt.reason

    def test_etui_zostaje_gdy_o_nie_prosimy(self):
        assert ocena("Etui do iPhone 15 silikonowe", "etui iphone 15").keep

    def test_akcesorium_jako_gratis_nie_dyskwalifikuje_towaru(self):
        """'iPhone 15 + etui gratis' to telefon z dodatkiem, nie etui."""
        assert ocena("iPhone 15 128GB - etui gratis", "iphone 15 128gb").keep
        assert ocena("iPhone 15 128GB, w zestawie szkło i etui", "iphone 15 128gb").keep
        assert ocena("iPhone 15 128GB + ładowarka w komplecie", "iphone 15 128gb").keep

    def test_ale_samo_akcesorium_dalej_odpada(self):
        assert not ocena("Etui silikonowe do iPhone 15, gratis folia", "iphone 15").keep


class TestPrzyneta:
    def _oferty(self, ceny):
        return [offer("olx", i, "PlayStation 5 slim", c) for i, c in enumerate(ceny, start=1)]

    def test_jedna_zlotowka_odpada_nawet_przy_trzech_ofertach(self):
        """Regresja: przy małej próbce filtr w ogóle nie działał i przynęta
        lądowała na pierwszym miejscu jako 'najtańsza'."""
        zostaje, odrzucone = drop_bait(self._oferty([1.0, 2700.0, 3199.0]))
        assert [o.total_price for o in zostaje] == [2700.0, 3199.0]
        assert "mediany" in odrzucone[0].rejected_because

    def test_tania_ale_prawdziwa_okazja_zostaje_przy_malej_probce(self):
        """Próg przy trzech ofertach musi być ostry, żeby nie zabijał okazji."""
        zostaje, odrzucone = drop_bait(self._oferty([300.0, 800.0, 2000.0]))
        assert len(zostaje) == 3 and odrzucone == []

    def test_przy_duzej_probce_prog_jest_lagodniejszy(self):
        zostaje, odrzucone = drop_bait(self._oferty([400.0, 1900.0, 1950.0, 2000.0, 2100.0]))
        assert [o.total_price for o in odrzucone] == [400.0]
        assert len(zostaje) == 4

    def test_dwie_oferty_to_za_malo_zeby_cokolwiek_wyrzucac(self):
        zostaje, odrzucone = drop_bait(self._oferty([1.0, 2700.0]))
        assert len(zostaje) == 2 and odrzucone == []


class TestOgloszenia:
    @pytest.mark.parametrize(
        "tytul,powod",
        [
            ("KUPIĘ iPhone 15 każdy stan", "kupie"),
            ("Zamienię iPhone 15 na Samsunga", "zamienie"),
            ("Naprawa iPhone 15 wymiana ekranu", "usluga"),
        ],
    )
    def test_nie_sprzedaz_odpada(self, tytul, powod):
        werdykt = ocena(tytul, "iphone 15")
        assert not werdykt.keep and powod in werdykt.reason

    def test_wykluczone_slowo_z_zapytania(self):
        werdykt = ocena("Rower górski damski Kross", "rower kross", excluded=["damski"])
        assert not werdykt.keep and "damski" in werdykt.reason
