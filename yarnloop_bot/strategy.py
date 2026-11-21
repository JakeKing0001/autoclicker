"""Sceglie quale rocchetto toccare.

Regole del gioco che il bot conosce:
- i rocchetti sul nastro raccolgono la lana del loro colore dal bordo del disegno;
- si puo' toccare solo il primo rocchetto di ogni colonna;
- due rocchetti collegati da un'asta partono solo quando sono entrambi in prima fila;
- il distributore (blocco grigio) sforna altri rocchetti nella casella davanti a se':
  la sua colonna ha la precedenza, e cio' che sta dietro non si puo' raggiungere.
"""
from __future__ import annotations

import time
from collections import Counter
from dataclasses import dataclass

from .config import Config
from .vision import Spool, State

GEN = "generator"
INF = 99


@dataclass
class Decision:
    spool: Spool | None
    reason: str
    fast: bool = False  # mossa di scavo: si puo' andare subito avanti


@dataclass
class _Tap:
    key: tuple
    colors: list
    t: float
    refills: bool  # davanti a un distributore: al suo posto ne arriva un altro


class Strategy:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.sent: list[tuple[float, str]] = []  # (tempo, colore) dei rocchetti partiti
        self.blocked: dict[tuple, float] = {}    # tocchi che non hanno funzionato -> fino a quando
        self.pending: list[_Tap] = []
        self._last_choice: tuple | None = None

    # ------------------------------------------------------------------ memoria
    def recent(self) -> Counter:
        now = time.monotonic()
        self.sent = [(t, c) for t, c in self.sent if now - t < self.cfg.recent_window]
        return Counter(c for _, c in self.sent)

    def remember(self, spool: Spool) -> None:
        if self._last_choice is None:
            return
        key, colors, refills = self._last_choice
        now = time.monotonic()
        self.pending.append(_Tap(key, colors, now, refills))
        for c in colors:
            if c:
                self.sent.append((now, c))

    @staticmethod
    def _key_slot(st: State, s: Spool):
        return ("slot", s.index, s.color)

    @staticmethod
    def _key_grid(st: State, cell):
        col = cell[0]
        sig = tuple(st.cells.get((col, r)) for r in range(cell[1], len(st.row_y)))
        return ("grid", col, sig)

    def _check_pending(self, st: State, present_keys: set) -> None:
        now = time.monotonic()
        keep = []
        for p in self.pending:
            if now - p.t < self.cfg.tap_check:
                keep.append(p)
                continue
            if p.key in present_keys and not p.refills:
                # il rocchetto e' ancora li': il tocco non ha funzionato
                self.blocked[p.key] = now + self.cfg.block_time
                for c in p.colors:
                    for i in range(len(self.sent) - 1, -1, -1):
                        if self.sent[i][1] == c:
                            del self.sent[i]
                            break
        self.pending = keep

    def _usable(self, key) -> bool:
        now = time.monotonic()
        if any(p.key == key for p in self.pending):
            return False  # appena toccato, aspetta di vedere se e' partito
        until = self.blocked.get(key)
        return until is None or until < now

    # ------------------------------------------------------------------ griglia
    @staticmethod
    def _front(st: State, col):
        rows = [r for (c, r) in st.cells if c == col]
        return min(rows) if rows else None

    def _depth(self, st: State, cell) -> int:
        """Quanti rocchetti vanno tolti prima che `cell` arrivi in prima fila."""
        col, row = cell
        f = self._front(st, col)
        if f is None:
            return INF
        for r in range(f, row):
            if st.cells.get((col, r)) == GEN:
                return INF
        return max(0, row - f)

    def _is_front(self, st: State, cell) -> bool:
        return cell in st.cells and st.cells[cell] != GEN and self._front(st, cell[0]) == cell[1]

    def _group(self, st: State, cell):
        """Rocchetti che partono toccando `cell` (o None se ora non si puo')."""
        if not self._is_front(st, cell):
            return None
        other = st.links.get(cell)
        if other is None:
            return [cell]
        if self._is_front(st, other):
            return [cell, other]
        return None

    def _spool(self, st: State, cell, reason_colors=None) -> Spool:
        x, y = st.tap_point(*cell)
        behind = [st.cells.get((cell[0], r)) for r in range(cell[1] + 1, len(st.row_y))]
        return Spool(st.cells[cell], x, y, "grid", cell[0], [b for b in behind if b], row=cell[1])

    # ------------------------------------------------------------------ decisione
    def decide(self, st: State) -> Decision:
        cfg = self.cfg
        self._last_choice = None

        slot_keys = {self._key_slot(st, s): s for s in st.slots if s}
        grid_fronts = [cell for cell in st.cells if self._is_front(st, cell)]
        grid_keys = {self._key_grid(st, c): c for c in grid_fronts}
        self._check_pending(st, set(slot_keys) | set(grid_keys))

        recent = self.recent()
        belt = Counter({c: max(st.belt[c], recent[c]) for c in set(st.belt) | set(recent)})
        on_belt = sum(belt.values())
        need = Counter({c: st.exposed[c] - belt[c] * cfg.yarn_per_spool for c in set(st.exposed) | set(belt)})

        def gain(colors):
            return sum(max(0, need[c]) for c in colors if c)

        def useful(colors):
            return any(c and st.exposed[c] > 0 for c in colors)

        if not st.level_visible:
            return Decision(None, "nessun livello visibile")
        if on_belt >= cfg.max_on_belt:
            return Decision(None, f"nastro pieno ({on_belt})")

        # 1) rimetti in gioco i rocchetti negli slot (liberano spazio)
        urgent = st.free_slots <= 1
        best = None
        for key, s in slot_keys.items():
            if not self._usable(key):
                continue
            colors = [s.color]
            j = st.slot_links.get(s.index)
            if j is not None and st.slots[j]:
                colors.append(st.slots[j].color)
            if not useful(colors) or (gain(colors) <= 0 and not urgent):
                continue
            score = (gain(colors), sum(st.exposed[c] for c in colors))
            if best is None or score > best[0]:
                best = (score, s, key, colors)
        if best:
            _, s, key, colors = best
            self._last_choice = (key, colors, False)
            return Decision(s, f"slot: {'+'.join(colors)} (esposti {st.exposed[s.color]})")

        # 2) prima fila: un colore che serve (precedenza alla colonna del distributore)
        best = None
        for cell in grid_fronts:
            key = self._key_grid(st, cell)
            group = self._group(st, cell)
            if group is None or not self._usable(key):
                continue
            colors = [st.cells[c] for c in group]
            if gain(colors) <= 0:
                continue
            gen = any(st.cells.get((c[0], c[1] + 1)) == GEN for c in group)
            useful_behind = sum(1 for r in (1, 2) if st.exposed[st.cells.get((cell[0], cell[1] + r))] > 0)
            score = (gen, gain(colors), useful_behind)
            if best is None or score > best[0]:
                best = (score, cell, key, colors, gen)
        if best:
            _, cell, key, colors, gen = best
            self._last_choice = (key, colors, gen)
            label = "+".join(colors) + (" (distributore)" if gen else "")
            return Decision(self._spool(st, cell), f"griglia: {label}")

        # 3) scavo: togli subito i rocchetti che coprono un colore utile (anche quelli collegati)
        plan = None
        for cell, color in st.cells.items():
            if color == GEN or st.exposed[color] <= 0 or self._group(st, cell) is not None:
                continue
            parts = [cell]
            other = st.links.get(cell)
            if other is not None:
                parts.append(other)
            cost = sum(self._depth(st, p) for p in parts)
            if cost >= INF:
                continue
            # cosa tocco adesso: il primo rocchetto di una colonna che va liberata
            move = None
            for p in parts:
                if self._depth(st, p) > 0:
                    f = (p[0], self._front(st, p[0]))
                    if self._group(st, f) is not None and self._usable(self._key_grid(st, f)):
                        move = f
                        break
            if move is None:
                continue
            score = (-cost, need[color])
            if plan is None or score > plan[0]:
                plan = (score, cost, move, color)
        if plan and plan[1] <= st.free_slots - cfg.reserve_slots:
            _, cost, move, color = plan
            group = self._group(st, move)
            colors = [st.cells[c] for c in group]
            gen = any(st.cells.get((c[0], c[1] + 1)) == GEN for c in group)
            self._last_choice = (self._key_grid(st, move), colors, gen)
            return Decision(self._spool(st, move), f"scavo: tolgo {'+'.join(colors)} per arrivare a {color}", fast=True)

        if on_belt > 0:
            return Decision(None, "aspetto che il nastro lavori")

        # 4) ultimo tentativo: distributore, poi il colore con piu' lana rimasta
        if st.free_slots >= cfg.reserve_slots + 2:
            best = None
            for cell in grid_fronts:
                key = self._key_grid(st, cell)
                group = self._group(st, cell)
                if group is None or not self._usable(key):
                    continue
                colors = [st.cells[c] for c in group]
                gen = any(st.cells.get((c[0], c[1] + 1)) == GEN for c in group)
                score = (gen, sum(st.remaining[c] for c in colors))
                if best is None or score > best[0]:
                    best = (score, cell, key, colors, gen)
            if best and (best[0][0] or best[0][1] > 0):
                _, cell, key, colors, gen = best
                self._last_choice = (key, colors, gen)
                return Decision(self._spool(st, cell), f"tentativo: {'+'.join(colors)}", fast=True)

        return Decision(None, "nessuna mossa sicura")
