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


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
