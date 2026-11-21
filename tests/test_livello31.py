"""Test sullo screenshot di esempio (python -m pytest oppure python tests/test_livello31.py)."""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from yarnloop_bot.config import Config  # noqa: E402
from yarnloop_bot.strategy import Strategy  # noqa: E402
from yarnloop_bot.vision import Analyzer  # noqa: E402

IMG = os.path.join(os.path.dirname(__file__), "..", "examples", "livello31.jpg")


def _state():
    return Analyzer(Config()).analyze(np.asarray(Image.open(IMG).convert("RGB")))


def test_slots():
    assert [s.color if s else None for s in _state().slots] == ["yellow", "skin", "green", None, None]


def test_grid():
    st = _state()
    assert [s.color for s in st.grid] == ["yellow", "black", "brown", "black"]
    assert [s.behind[:1] for s in st.grid] == [["pink"], ["green"], ["pink"], ["green"]]


def test_belt():
    assert dict(_state().belt) == {"pink": 1, "red": 1, "skin": 1}


def test_exposed():
    ex = _state().exposed
    assert ex["black"] > ex["green"] > 0
    assert ex["yellow"] > 0 and ex["pink"] > 0
    assert ex["brown"] == 0 and ex["skin"] == 0  # marrone e pelle sono all'interno


def test_numbers():
    st = _state()
    assert st.belt_free == 2
    assert [s.count if s else None for s in st.slots] == [7, 9, 5, None, None]
    assert [g.count for g in st.grid] == [10, 10, 10, 10]


def test_burst_fills_belt():
    """Nastro con 2 posti liberi: due tocchi, prima gli slot che possono finire."""
    dec = Strategy(Config()).decide(_state())
    assert len(dec.moves) == 2
    assert all(m.spool.where == "slot" for m in dec.moves)
    assert {m.spool.color for m in dec.moves} == {"green", "yellow"}


def test_dig_without_waiting():
    """Se serve solo un colore nascosto, toglie subito il rocchetto davanti (anche col nastro occupato)."""
    from collections import Counter
    st = _state()
    st.exposed = Counter({"pink": 10})       # il rosa e' dietro a giallo e marrone
    st.slots = [None] * 5
    st.belt = Counter({"red": 1})            # il nastro sta lavorando
    st.belt_free = 1
    dec = Strategy(Config()).decide(st)
    assert len(dec.moves) == 1
    assert dec.spool.where == "grid" and dec.spool.color in ("yellow", "brown")


IMG33 = os.path.join(os.path.dirname(__file__), "..", "examples", "livello33.jpg")


def _state33():
    return Analyzer(Config()).analyze(np.asarray(Image.open(IMG33).convert("RGB")))


def test_33_white_and_generator():
    st = _state33()
    assert st.exposed["white"] > 20
    assert [s.color if s else None for s in st.slots] == ["red", "white", None, None, None]
    assert st.cells[(1, 0)] == "white" and st.cells[(1, 1)] == "generator"
    assert [s.color for s in st.grid] == ["white", "orange", "orange"]


def test_33_links():
    st = _state33()
    assert st.slot_links == {0: 1, 1: 0}
    assert st.links[(2, 1)] == (1, 2)       # rosa - giallo
    assert st.links[(3, 0)] == (2, 3)       # arancio - rocchetto nascosto sotto la barra


def test_33_numbers():
    st = _state33()
    assert st.belt_free == 4
    assert [s.count if s else None for s in st.slots] == [11, 0, None, None, None]
    assert st.counts[(1, 0)] == 40 and st.counts[(1, 1)] == 3  # bianco 40, distributore 3


def test_33_burst():
    st = _state33()
    dec = Strategy(Config()).decide(st)
    keys = [(m.spool.where, m.spool.x) for m in dec.moves]
    # rosso+bianco negli slot: il bianco e' gia' pieno (0) e il rosso non e' esposto -> inutile
    assert all(m.spool.where == "grid" for m in dec.moves), dec
    # il bianco 40 davanti al distributore e l'arancio libero; l'arancio collegato a uno nascosto no
    assert keys[0] == ("grid", 365) and ("grid", 540) in keys and ("grid", 714) not in keys


IMG35 = os.path.join(os.path.dirname(__file__), "..", "examples", "livello35_bianco50.jpg")


def test_35_white_first():
    """Nel video il bianco 50 restava fermo mentre il bot rimandava in giro rocchetti quasi inutili."""
    st = Analyzer(Config()).analyze(np.asarray(Image.open(IMG35).convert("RGB")))
    assert st.cells[(2, 0)] == "white" and st.counts[(2, 0)] == 50
    assert st.cells[(1, 1)] == "mystery"
    st.belt_free = 1
    dec = Strategy(Config()).decide(st)
    assert [(m.spool.color, m.spool.count) for m in dec.moves] == [("white", 50)], dec


def test_finisher_allowed_with_full_slots():
    """Slot quasi pieni: il bianco 30 (finisce, non torna negli slot) va mandato lo stesso."""
    img = os.path.join(os.path.dirname(__file__), "..", "examples", "livello35_slotpieni.jpg")
    st = Analyzer(Config()).analyze(np.asarray(Image.open(img).convert("RGB")))
    assert st.free_slots == 1 and st.belt_free == 1
    assert len(st.belt_spools) >= 3  # numeri accanto ai rocchetti sul nastro
    dec = Strategy(Config()).decide(st)
    assert [(m.spool.color, m.spool.count) for m in dec.moves] == [("white", 30)], dec


def test_generator_is_emptied():
    """Video 3: col nastro mezzo vuoto il bot aspettava e il distributore restava a 8 per tutta la partita."""
    img = os.path.join(os.path.dirname(__file__), "..", "examples", "livello35_bloccato.jpg")
    st = Analyzer(Config()).analyze(np.asarray(Image.open(img).convert("RGB")))
    assert st.cells[(2, 1)] == "generator" and st.counts[(2, 1)] == 8
    st.belt_spools = [("brown", 4)]  # il marrone 4 finisce, quindi non torna
    dec = Strategy(Config()).decide(st)
    assert any(m.spool.column == 2 for m in dec.moves), dec


def test_slot_glow_is_not_a_link():
    """Il bordo rosso che lampeggia con gli slot quasi pieni non e' un'asta tra due rocchetti."""
    st = _state33()
    assert st.slot_links == {0: 1, 1: 0}


def test_no_taps_outside_level():
    """Sotto i popup (Spazio finito, offerte) il contatore non si legge: nessun tocco sulla griglia."""
    for f in ("spazio_finito", "vuoi_procedere", "hai_perso", "home_offerta"):
        img = os.path.join(os.path.dirname(__file__), "..", "examples", "screens", f + ".jpg")
        st = Analyzer(Config()).analyze(np.asarray(Image.open(img).convert("RGB")))
        assert st.belt_free is None, f
        assert Strategy(Config()).decide(st).moves == [], f


def test_screens():
    from yarnloop_bot.screens import classify
    want = {"hai_perso": (540, 1380), "acquisto_annullato": (540, 1380), "vuoi_procedere": "X",
            "home_offerta": "X", "home": (540, 1950), "spazio_finito": None,
            "home_ads": (540, 1950),  # il cerchio rosso col "%" in Home e' un'offerta, non una X
            "home_bonanza": "X", "dopo_riprova": None}
    for f, exp in want.items():
        img = os.path.join(os.path.dirname(__file__), "..", "examples", "screens", f + ".jpg")
        kind, pt = classify(np.asarray(Image.open(img).convert("RGB")))
        if exp is None:
            assert kind is None, f
        elif exp == "X":
            assert kind.startswith("chiudo") and pt[0] > 950 and 450 < pt[1] < 560, (f, pt)
        else:
            assert pt == exp, (f, pt)
    for f in ("livello31", "livello33", "livello35_inizio", "livello35_bianco50", "livello35_fine"):
        img = os.path.join(os.path.dirname(__file__), "..", "examples", f + ".jpg")
        assert classify(np.asarray(Image.open(img).convert("RGB")))[0] is None, f


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
