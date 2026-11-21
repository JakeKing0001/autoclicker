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
        self.sent: list[tuple[float, str, int]] = []  # (tempo, colore, lana) dei rocchetti partiti
        self.blocked: dict[tuple, float] = {}
        self.pending: list[_Tap] = []

    # ------------------------------------------------------------------ memoria
    def _recent(self) -> Counter:
        now = time.monotonic()
        self.sent = [e for e in self.sent if now - e[0] < self.cfg.recent_window]
        return Counter(c for _, c, _ in self.sent)

    def remember(self, move: Move) -> None:
        now = time.monotonic()
        self.pending.append(_Tap(move.key, move.colors, now, move.refills))
        for c in move.colors:
            if c:
                self.sent.append((now, c, move.spool.count or self.cfg.yarn_per_spool))

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
        cfg = self.cfg
        slot_moves = {}
        for s in st.slots:
            if s:
                slot_moves[self._key_slot(s)] = s
        fronts = [cell for cell in st.cells if self._is_front(st, cell)]
        self._check_pending(set(slot_moves) | {self._key_grid(st, c) for c in fronts})

        if not st.level_visible:
            return Decision([], "nessun livello visibile")

        recent = self._recent()
        if st.belt_free is not None:
            seats = st.belt_free
        else:
            on_belt = sum(max(st.belt[c], recent[c]) for c in set(st.belt) | set(recent))
            seats = cfg.max_on_belt - on_belt
        if seats <= 0:
            return Decision([], "nastro pieno")

        # lana ancora "libera" per colore = esposta - quella che stanno gia' prendendo i rocchetti partiti
        taken = Counter()
        for _, c, n in self.sent:
            taken[c] += n
        avail = Counter({c: st.exposed[c] - taken[c] for c in st.exposed})

        moves: list[Move] = []
        spare_slots = st.free_slots - cfg.reserve_slots  # rocchetti "inutili" che possono tornare negli slot
        done_slots, done_cols = set(), set()

        def add(m: Move) -> bool:
            nonlocal seats
            if m.seats > seats:
                return False
            moves.append(m)
            seats -= m.seats
            for c in m.colors:
                if c:
                    avail[c] -= m.spool.count or cfg.yarn_per_spool
            return True

        # 1) slot: rimetti in giro tutto quello che ha lana esposta, prima chi puo' finire
        cands = []
        for key, s in slot_moves.items():
            if not self._usable(key) or s.index in done_slots:
                continue
            j = st.slot_links.get(s.index)
            partner = st.slots[j] if j is not None and st.slots[j] else None
            colors = [s.color] + ([partner.color] if partner else [])
            if not any(st.exposed[c] > 0 for c in colors):
                continue
            n = s.count if s.count is not None else cfg.yarn_per_spool
            finishes = n <= avail[s.color]
            cands.append(((finishes, -n if finishes else st.exposed[s.color]), s, key, colors, partner))
        for _, s, key, colors, partner in sorted(cands, key=lambda t: t[0], reverse=True):
            if s.index in done_slots:
                continue
            if add(Move(s, key, colors, False, len(colors))):
                done_slots.add(s.index)
                if partner:
                    done_slots.add(partner.index)

        # 2) prima fila con un colore esposto (prima il distributore, poi chi serve di piu')
        cands = []
        for cell in fronts:
            group = self._group(st, cell)
            if group is None or not self._usable(self._key_grid(st, cell)):
                continue
            m = self._grid_move(st, cell)
            if not any(st.exposed[c] > 0 for c in m.colors):
                continue
            gain = sum(max(0, avail[c]) for c in m.colors)
            cands.append(((m.refills, gain), cell, m, group))
        for _, cell, m, group in sorted(cands, key=lambda t: t[0], reverse=True):
            if any(c[0] in done_cols for c in group):
                continue
            if add(m):
                done_cols.update(c[0] for c in group)

        # 3) scavo: togli i rocchetti che coprono un colore utile (uno per colonna)
        plans = []
        for cell, color in st.cells.items():
            if color == GEN or st.exposed[color] <= 0 or self._group(st, cell) is not None:
                continue
            parts = [cell] + ([st.links[cell]] if cell in st.links else [])
            cost = sum(self._depth(st, p) for p in parts)
            if cost >= INF:
                continue
            for p in parts:
                if self._depth(st, p) <= 0:
                    continue
                f = (p[0], self._front(st, p[0]))
                if self._group(st, f) is not None and self._usable(self._key_grid(st, f)):
                    plans.append((cost, -avail[color], f, color))
                    break
        for cost, _, f, color in sorted(plans):
            if f[0] in done_cols or cost > spare_slots:
                continue
            m = self._grid_move(st, f)
            # un rocchetto tolto per scavare, se il suo colore non serve, finira' negli slot
            useless = not any(st.exposed[c] > 0 for c in m.colors)
            if useless and spare_slots <= 0:
                continue
            if add(m):
                done_cols.update(c[0] for c in self._group(st, f))
                if useless:
                    spare_slots -= 1

        # 4) niente da fare e nastro vuoto: prova col distributore o col colore che c'e' di piu'
        on_belt_now = sum(recent.values()) + sum(st.belt.values())
        if not moves and on_belt_now == 0 and st.free_slots >= cfg.reserve_slots + 2:
            best = None
            for cell in fronts:
                if self._group(st, cell) is None or not self._usable(self._key_grid(st, cell)):
                    continue
                m = self._grid_move(st, cell)
                score = (m.refills, sum(st.remaining[c] for c in m.colors))
                if best is None or score > best[0]:
                    best = (score, m)
            if best and (best[0][0] or best[0][1] > 0):
                add(best[1])

        if not moves:
            return Decision([], "aspetto che il nastro lavori" if on_belt_now else "nessuna mossa sicura")
        desc = ", ".join(f"{'+'.join(m.colors)}{'' if m.spool.count is None else ' ' + str(m.spool.count)}"
                         f"{' (slot)' if m.spool.where == 'slot' else ''}" for m in moves)
        return Decision(moves, f"{len(moves)} tocchi: {desc}")
