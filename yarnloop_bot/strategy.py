"""Sceglie quali rocchetti toccare.

Tattica: tenere il nastro sempre pieno. Ad ogni screenshot si toccano subito tanti rocchetti
quanti sono i posti liberi sul nastro (contatore "N/5"), cosi' ce ne sono sempre in giro:
1. prima quelli negli slot (prima quelli che possono finire, numero <= lana esposta);
2. poi quelli in prima fila con un colore esposto (prima la colonna del distributore);
3. poi lo scavo: si tolgono i rocchetti che coprono un colore utile, uno per colonna,
   tenendo sempre almeno uno slot libero.

Regole del gioco che il bot conosce:
- si puo' toccare solo il primo rocchetto di ogni colonna;
- due rocchetti collegati da un'asta partono solo quando sono entrambi in prima fila;
- il distributore (blocco grigio) sforna rocchetti nella casella davanti a se';
- se un rocchetto toccato non parte, per un po' viene lasciato stare.
"""
from __future__ import annotations

import time
from collections import Counter
from dataclasses import dataclass, field

from .config import Config
from .vision import Spool, State

GEN = "generator"
INF = 99


@dataclass
class Move:
    spool: Spool
    key: tuple
    colors: list
    refills: bool = False  # davanti a un distributore: al suo posto ne arriva un altro
    seats: int = 1         # posti sul nastro occupati (2 per i rocchetti collegati)
    returns: int = 0       # quanti probabilmente torneranno negli slot (non finiscono)


@dataclass
class Decision:
    moves: list = field(default_factory=list)
    reason: str = ""

    @property
    def spool(self):
        return self.moves[0].spool if self.moves else None


@dataclass
class _Tap:
    key: tuple
    colors: list
    t: float
    refills: bool


class Strategy:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.sent: list[tuple] = []  # (tempo, colore, lana, torna negli slot 0/1) dei rocchetti partiti
        self.blocked: dict[tuple, float] = {}
        self.pending: list[_Tap] = []

    # ------------------------------------------------------------------ memoria
    def _recent(self) -> Counter:
        now = time.monotonic()
        self.sent = [e for e in self.sent if now - e[0] < max(self.cfg.recent_window, self.cfg.arrive_time)]
        return Counter(e[1] for e in self.sent)

    def remember(self, move: Move) -> None:
        now = time.monotonic()
        self.pending.append(_Tap(move.key, move.colors, now, move.refills))
        for i, c in enumerate(move.colors):
            if c:
                n = move.spool.count if move.spool.count is not None else self.cfg.yarn_per_spool
                self.sent.append((now, c, n, 1 if i < move.returns else 0))

    @staticmethod
    def _key_slot(s: Spool):
        return ("slot", s.index, s.color)

    @staticmethod
    def _key_grid(st: State, cell):
        col = cell[0]
        sig = tuple(st.cells.get((col, r)) for r in range(cell[1], len(st.row_y)))
        return ("grid", col, sig)

    def _check_pending(self, present_keys: set) -> None:
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
        if any(p.key == key for p in self.pending):
            return False  # appena toccato: aspetta di vedere se e' partito
        until = self.blocked.get(key)
        return until is None or until < time.monotonic()

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

    def _grid_move(self, st: State, cell) -> Move:
        group = self._group(st, cell)
        x, y = st.tap_point(*cell)
        behind = [st.cells.get((cell[0], r)) for r in range(cell[1] + 1, len(st.row_y))]
        sp = Spool(st.cells[cell], x, y, "grid", cell[0], [b for b in behind if b], row=cell[1],
                   count=st.counts.get(cell))
        refills = any(st.cells.get((c[0], c[1] + 1)) == GEN for c in group)
        return Move(sp, self._key_grid(st, cell), [st.cells[c] for c in group], refills, len(group))

    # ------------------------------------------------------------------ decisione
    def decide(self, st: State) -> Decision:
        """Riempie i posti liberi del nastro con i rocchetti che raccolgono piu' lana.

        Valore di un rocchetto = lana che puo' davvero prendere = min(numero, lana esposta libera).
        Un rocchetto che non riesce a finire torna negli slot: se ne mandano solo finche'
        c'e' posto negli slot (contando anche quelli gia' in giro che torneranno).
        """
        cfg = self.cfg
        slot_moves = {self._key_slot(s): s for s in st.slots if s}
        fronts = [cell for cell in st.cells if self._is_front(st, cell)]
        self._check_pending(set(slot_moves) | {self._key_grid(st, c) for c in fronts})

        if st.belt_free is None:
            # il contatore del nastro non si vede: popup, pubblicita', fine livello... non toccare niente
            return Decision([], "non e' la schermata del livello")
        seats = st.belt_free
        if seats <= 0:
            return Decision([], "nastro pieno")

        now = time.monotonic()
        # lana che i rocchetti gia' sul nastro stanno per prendere (numeri letti accanto a loro),
        # piu' quelli appena toccati che non sono ancora arrivati sul nastro
        reach = Counter({c: int(st.exposed[c] * cfg.reach) for c in st.exposed})
        taken = Counter()
        on_belt = sorted(st.belt_spools, key=lambda e: e[1])
        for e in self.sent:
            if now - e[0] < cfg.arrive_time:
                on_belt.append((e[1], e[2]))
        returning = 0
        for c, n in sorted(on_belt, key=lambda e: e[1]):
            if taken[c] + n > reach[c]:
                returning += 1  # non finira': tornera' in uno slot
            taken[c] += n
        avail = Counter({c: max(0, reach[c] - taken[c]) for c in reach})
        # slot che restano liberi anche quando tornano quelli che non finiranno
        room = st.free_slots - returning - cfg.reserve_slots

        def n_of(sp):
            return sp.count if sp.count is not None else cfg.yarn_per_spool

        # --- candidati
        cands = []  # (move, kind)
        for key, s in slot_moves.items():
            if not self._usable(key):
                continue
            j = st.slot_links.get(s.index)
            partner = st.slots[j] if j is not None and st.slots[j] else None
            if partner and partner.index < s.index:
                continue  # la coppia si tocca una volta sola
            colors = [s.color] + ([partner.color] if partner else [])
            counts = [n_of(s)] + ([n_of(partner)] if partner else [])
            cands.append((Move(s, key, colors, False, len(colors)), "slot", counts))
        for cell in fronts:
            if self._group(st, cell) is None or not self._usable(self._key_grid(st, cell)):
                continue
            m = self._grid_move(st, cell)
            counts = [st.counts.get(c, cfg.yarn_per_spool) for c in self._group(st, cell)]
            cands.append((m, "grid", counts))
        # mosse di scavo: rocchetto davanti a un colore utile
        dig_cols = {}
        for cell, color in st.cells.items():
            if color in (GEN, "mystery") or st.exposed[color] <= 0 or self._group(st, cell) is not None:
                continue
            parts = [cell] + ([st.links[cell]] if cell in st.links else [])
            cost = sum(self._depth(st, p) for p in parts)
            if cost >= INF:
                continue
            for p in parts:
                if self._depth(st, p) > 0:
                    f = (p[0], self._front(st, p[0]))
                    if self._group(st, f) is not None:
                        dig_cols[f] = min(dig_cols.get(f, INF), cost)
                    break

        moves, used_cols, used_slots = [], set(), set()
        picked_value = 0
        while seats > 0:
            best = None
            for m, kind, counts in cands:
                if m.seats > seats:
                    continue
                if kind == "slot" and m.spool.index in used_slots:
                    continue
                cols = set()
                if kind == "grid":
                    cols = {c[0] for c in ([(m.spool.column, m.spool.row)] + ([st.links[(m.spool.column, m.spool.row)]]
                            if (m.spool.column, m.spool.row) in st.links else []))}
                    if cols & used_cols:
                        continue
                gain = sum(min(n, avail[c]) for c, n in zip(m.colors, counts) if c)
                finishes = all(n <= avail[c] for c, n in zip(m.colors, counts) if c)
                cell = (m.spool.column, m.spool.row)
                dig = kind == "grid" and cell in dig_cols
                if not dig and (gain <= 0 or (gain < cfg.min_gain and not finishes)):
                    continue  # occuperebbe un posto sul nastro per niente
                # chi non finisce tornera' in uno slot: serve spazio (gli slot liberano il loro posto)
                new_returns = 0 if finishes else len(m.colors)
                frees = len(m.colors) if kind == "slot" else 0
                if kind == "grid" and new_returns > 0 and new_returns > room:
                    continue
                score = gain
                if finishes:
                    score += 5
                if kind == "slot":
                    score += 3 * (len(st.slots) - st.free_slots)  # piu' gli slot sono pieni, piu' conviene svuotarli
                if m.refills:
                    score += 4
                if dig:
                    score += max(1, 6 - 2 * dig_cols[cell])
                if best is None or score > best[0]:
                    best = (score, m, kind, counts, cols, new_returns, frees, gain)
            if best is None:
                break
            score, m, kind, counts, cols, new_returns, frees, gain = best
            moves.append(m)
            m.returns = new_returns
            seats -= m.seats
            used_cols |= cols
            if kind == "slot":
                used_slots.add(m.spool.index)
                j = st.slot_links.get(m.spool.index)
                if j is not None:
                    used_slots.add(j)
            room += frees - new_returns
            for c, n in zip(m.colors, counts):
                if c:
                    avail[c] = max(0, avail[c] - n)
            picked_value += gain

        on_belt = (st.belt_cap or cfg.max_on_belt) - st.belt_free
        if not moves and on_belt == 0 and room >= 1:
            # nastro vuoto e niente di utile: distributore o colore con piu' lana nel disegno
            best = None
            for m, kind, counts in cands:
                if kind != "grid":
                    continue
                score = (m.refills, sum(st.remaining[c] for c in m.colors if c))
                if best is None or score > best[0]:
                    best = (score, m)
            if best and (best[0][0] or best[0][1] > 0):
                best[1].returns = len(best[1].colors)
                moves.append(best[1])

        if not moves:
            return Decision([], "aspetto che il nastro lavori" if on_belt else "nessuna mossa sicura")
        desc = ", ".join(f"{'+'.join(c for c in m.colors if c)}{'' if m.spool.count is None else ' ' + str(m.spool.count)}"
                         f"{' (slot)' if m.spool.where == 'slot' else ''}" for m in moves)
        return Decision(moves, f"{len(moves)} tocchi: {desc}")
