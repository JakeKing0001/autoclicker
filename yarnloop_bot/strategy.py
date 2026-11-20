"""Sceglie quale rocchetto toccare."""
from __future__ import annotations

import time
from collections import Counter
from dataclasses import dataclass

from .config import Config
from .vision import Spool, State


@dataclass
class Decision:
    spool: Spool | None
    reason: str
    fast: bool = False  # mossa di scavo: si puo' andare subito avanti


class Strategy:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.sent: list[tuple[float, str]] = []  # (tempo, colore) dei tocchi recenti

    def recent(self) -> Counter:
        now = time.monotonic()
        self.sent = [(t, c) for t, c in self.sent if now - t < self.cfg.recent_window]
        return Counter(c for _, c in self.sent)

    def remember(self, spool: Spool) -> None:
        self.sent.append((time.monotonic(), spool.color))

    def decide(self, st: State) -> Decision:
        cfg = self.cfg
        recent = self.recent()
        belt = Counter({c: max(st.belt[c], recent[c]) for c in set(st.belt) | set(recent)})
        on_belt = sum(belt.values())
        need = {c: st.exposed[c] - belt[c] * cfg.yarn_per_spool for c in set(st.exposed) | set(belt)}

        if not st.level_visible:
            return Decision(None, "nessun livello visibile")
        if on_belt >= cfg.max_on_belt:
            return Decision(None, f"nastro pieno ({on_belt})")

        slot_spools = [s for s in st.slots if s]
        # 1) rimetti in gioco i rocchetti negli slot (liberano spazio)
        cands = [s for s in slot_spools if st.exposed[s.color] > 0]
        urgent = st.free_slots <= 1
        cands = [s for s in cands if need.get(s.color, 0) > 0 or urgent]
        if cands:
            best = max(cands, key=lambda s: (need.get(s.color, 0), st.exposed[s.color]))
            return Decision(best, f"slot: {best.color} (esposti {st.exposed[best.color]})")

        # 2) rocchetto in prima fila con un colore che serve
        cands = [s for s in st.grid if need.get(s.color, 0) > 0]
        if cands:
            # a parita', preferisci quelli che hanno dietro colori utili
            def score(s):
                bonus = sum(1 for b in s.behind[:2] if st.exposed[b] > 0)
                return (need[s.color], bonus)
            best = max(cands, key=score)
            return Decision(best, f"griglia: {best.color} (esposti {st.exposed[best.color]})")

        # 3) scavo: non aspettare, togli subito i rocchetti che coprono un colore utile.
        #    costo = rocchetti da togliere; serve uno slot libero per ognuno (+ riserva)
        dig, dig_cost, dig_target = None, 99, None
        for s in st.grid:
            for d, c in enumerate(s.behind):
                if st.exposed[c] <= 0:
                    continue
                cost = d + 1
                if cost < dig_cost or (cost == dig_cost and need.get(c, 0) > need.get(dig_target, 0)):
                    dig, dig_cost, dig_target = s, cost, c
                break
        if dig is not None and dig_cost <= st.free_slots - cfg.reserve_slots:
            return Decision(dig, f"scavo: tolgo {dig.color} per arrivare a {dig_target}", fast=True)

        if on_belt > 0:
            return Decision(None, "aspetto che il nastro lavori")

        # 4) ultimo tentativo: il colore con piu' lana rimasta nel disegno
        if st.grid and st.free_slots >= cfg.reserve_slots + 2:
            best = max(st.grid, key=lambda s: st.remaining[s.color])
            if st.remaining[best.color] > 0:
                return Decision(best, f"tentativo: {best.color}", fast=True)

        return Decision(None, "nessuna mossa sicura")
