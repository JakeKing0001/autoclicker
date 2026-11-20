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

        # se c'e' gia' qualcosa sul nastro che lavora, aspetta
        if on_belt > 0:
            return Decision(None, "aspetto che il nastro lavori")

        # 3) sblocco: togli un rocchetto davanti per arrivare a un colore utile
        if st.free_slots >= cfg.min_free_slots_for_unblock:
            best, depth = None, 99
            for s in st.grid:
                for d, c in enumerate(s.behind):
                    if st.exposed[c] > 0 and d < depth:
                        best, depth = s, d
                        break
            if best is not None:
                return Decision(best, f"sblocco: tolgo {best.color} per arrivare a {best.behind[depth]}")
            # 4) ultima spiaggia: il colore con piu' lana rimasta nel disegno
            if st.grid and st.free_slots >= cfg.min_free_slots_for_unblock + 1:
                best = max(st.grid, key=lambda s: st.remaining[s.color])
                if st.remaining[best.color] > 0:
                    return Decision(best, f"tentativo: {best.color}")

        return Decision(None, "nessuna mossa sicura")
