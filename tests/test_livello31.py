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


def test_decision_is_slot():
    dec = Strategy(Config()).decide(_state())
    assert dec.spool is not None and dec.spool.where == "slot"
    assert dec.spool.color in ("green", "yellow")


def test_dig_without_waiting():
    """Se serve solo un colore nascosto, toglie subito il rocchetto davanti (anche col nastro occupato)."""
    from collections import Counter
    st = _state()
    st.exposed = Counter({"pink": 10})       # il rosa e' dietro a giallo e marrone
    st.slots = [None] * 5
    st.belt = Counter({"red": 1})            # il nastro sta lavorando
    dec = Strategy(Config()).decide(st)
    assert dec.spool is not None and dec.fast
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


def test_33_decision():
    st = _state33()
    dec = Strategy(Config()).decide(st)
    # bianco+rosso negli slot servono (il bianco e' molto esposto)
    assert dec.spool is not None and dec.spool.where == "slot"
    # senza slot: il bianco davanti al distributore ha la precedenza,
    # l'arancio collegato a un rocchetto nascosto non si puo' toccare
    st.slots = [None] * 5
    st.slot_links = {}
    dec = Strategy(Config()).decide(st)
    assert (dec.spool.x, dec.spool.color) == (365, "white"), dec


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
