"""Analisi di uno screenshot di Yarn Loop."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field

import numpy as np

from . import colors as C
from .config import Config


@dataclass
class Spool:
    color: str
    x: int          # punto da toccare
    y: int
    where: str      # "slot" o "grid"
    column: int = -1
    behind: list = field(default_factory=list)  # colori dei rocchetti dietro (solo griglia)


@dataclass
class State:
    width: int
    height: int
    exposed: Counter            # colore -> blocchi raggiungibili dal nastro
    remaining: Counter          # colore -> blocchi totali ancora nel disegno
    slots: list                 # list[Spool | None]
    grid: list                  # list[Spool] (solo i rocchetti in prima fila)
    belt: Counter               # colore -> rocchetti visti sul nastro (i neri non si vedono)
    probes: list = field(default_factory=list)  # (x, y, colore) per il debug

    @property
    def free_slots(self) -> int:
        return sum(1 for s in self.slots if s is None)

    @property
    def level_visible(self) -> bool:
        return sum(self.remaining.values()) > 0 or bool(self.grid) or any(self.slots)


def _px(rect, w, h):
    x0, y0, x1, y1 = rect
    return int(x0 * w), int(y0 * h), int(x1 * w), int(y1 * h)


class Analyzer:
    def __init__(self, cfg: Config):
        self.cfg = cfg

    # ------------------------------------------------------------------ disegno
    def picture(self, img: np.ndarray, cls: np.ndarray):
        h, w = cls.shape
        x0, y0, x1, y1 = _px(self.cfg.picture, w, h)
        sub = cls[y0:y1, x0:x1]
        ignored = [C.index(n) for n in C.IGNORED]
        fg = ~np.isin(sub, ignored)

        cell = max(4, int(self.cfg.cell * w))
        probe = max(2, int(cell * 0.45))
        step = max(2, cell // 4)

        remaining = Counter()
        # Colori totali: campiona una griglia fitta, raggruppata per cella
        ys, xs = np.nonzero(fg[::step, ::step])
        if len(ys):
            vals = sub[::step, ::step][ys, xs]
            cells = {}
            for yy, xx, v in zip(ys * step, xs * step, vals):
                if v == C.index("black") and not self._is_black_block(sub, yy, xx, probe):
                    continue
                cells.setdefault((yy // cell, xx // cell), Counter())[int(v)] += 1
            for cnt in cells.values():
                remaining[C.name(cnt.most_common(1)[0][0])] += 1

        exposed_cells = {}
        probes = []

        def sample(py, px):
            if not (0 <= py < sub.shape[0] and 0 <= px < sub.shape[1]):
                return
            # Scarta cose sottili (es. il filo che va dal blocco al rocchetto)
            q = max(2, int(cell * 0.4))
            if fg[max(0, py - q): py + q + 1, max(0, px - q): px + q + 1].mean() < 0.85:
                return
            r = max(2, cell // 5)
            patch = img[y0 + max(0, py - r): y0 + py + r + 1, x0 + max(0, px - r): x0 + px + r + 1]
            name, _ = C.dominant(patch, min_fraction=0.3)
            if name is None:
                return
            key = (py // cell, px // cell)
            exposed_cells.setdefault(key, Counter())[name] += 1
            probes.append((x0 + px, y0 + py, name))

        # Righe: da sinistra e da destra
        for yy in range(0, fg.shape[0], step):
            idx = np.flatnonzero(fg[yy])
            if len(idx) == 0:
                continue
            # se tocca il bordo della zona e' qualcosa che entra da fuori (il filo)
            if idx[0] > 0:
                sample(yy, idx[0] + probe)
            if idx[-1] < fg.shape[1] - 1:
                sample(yy, idx[-1] - probe)
        # Colonne: dall'alto e dal basso
        for xx in range(0, fg.shape[1], step):
            idx = np.flatnonzero(fg[:, xx])
            if len(idx) == 0:
                continue
            if idx[0] > 0:
                sample(idx[0] + probe, xx)
            if idx[-1] < fg.shape[0] - 1:
                sample(idx[-1] - probe, xx)

        exposed = Counter()
        for cnt in exposed_cells.values():
            exposed[cnt.most_common(1)[0][0]] += 1
        return exposed, remaining, probes

    @staticmethod
    def _is_black_block(sub, yy, xx, probe):
        """Distingue un blocco nero dal contorno nero sottile dei blocchi colorati."""
        ys = slice(max(0, yy - probe // 2), yy + probe // 2 + 1)
        xs = slice(max(0, xx - probe // 2), xx + probe // 2 + 1)
        patch = sub[ys, xs]
        return (patch == C.index("black")).mean() > 0.7

    # ------------------------------------------------------------------ slot
    def slots(self, img: np.ndarray):
        h, w = img.shape[:2]
        _, y0, _, y1 = _px(self.cfg.slots, w, h)
        half = int(self.cfg.spool_w * w / 2 * 0.85)
        out = []
        for fx in self.cfg.slot_x:
            cx = int(fx * w)
            patch = img[y0:y1, max(0, cx - half): cx + half]
            name, _ = C.dominant(patch, min_fraction=0.25)
            out.append(Spool(name, cx, (y0 + y1) // 2, "slot") if name else None)
        return out

    # ------------------------------------------------------------------ griglia
    def grid(self, img: np.ndarray, cls: np.ndarray):
        h, w = cls.shape
        x0, y0, x1, y1 = _px(self.cfg.grid, w, h)
        sub = cls[y0:y1, x0:x1]
        ignored = [C.index(n) for n in C.IGNORED]
        fg = ~np.isin(sub, ignored)
        spool_w = int(self.cfg.spool_w * w)
        pitch = int(self.cfg.spool_pitch * h)

        # Colonne: dove c'e' "roba" non grigia
        prof = fg.mean(axis=0)
        cols = _runs(prof > 0.12, min_len=spool_w // 2)
        result = []
        for ci, (a, b) in enumerate(cols):
            cx = (a + b) // 2
            strip = fg[:, max(0, cx - spool_w // 4): cx + spool_w // 4]
            rows = strip.mean(axis=1) > 0.5
            ry = np.flatnonzero(rows)
            if len(ry) == 0:
                continue
            top = int(ry[0])
            stack = []
            y = top
            while y + pitch * 0.5 < sub.shape[0]:
                py0, py1 = y0 + y, y0 + min(sub.shape[0], y + int(pitch * 0.85))
                patch = img[py0:py1, x0 + cx - spool_w // 2 + 8: x0 + cx + spool_w // 2 - 8]
                name, _ = C.dominant(patch, min_fraction=0.2)
                if not name:
                    break
                stack.append(name)
                y += pitch
            if not stack:
                continue
            tap_y = y0 + top + int(pitch * 0.35)
            result.append(Spool(stack[0], x0 + cx, tap_y, "grid", ci, stack[1:]))
        return result

    # ------------------------------------------------------------------ nastro
    def belt(self, img: np.ndarray):
        h, w = img.shape[:2]
        x0, y0, x1, y1 = _px(self.cfg.belt, w, h)
        ignore = [_px(r, w, h) for r in self.cfg.belt_ignore]
        r = max(3, int(0.012 * w))
        step = max(3, r)
        # Rettangolo con gli angoli smussati (il nastro e' arrotondato)
        c = int(0.08 * w)
        poly = [(x0 + c, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1 - c),
                (x1 - c, y1), (x0 + c, y1), (x0, y1 - c), (x0, y0 + c), (x0 + c, y0)]
        pts = []
        for (ax, ay), (bx, by) in zip(poly, poly[1:]):
            n = max(1, int(np.hypot(bx - ax, by - ay) // step))
            pts += [(int(ax + (bx - ax) * i / n), int(ay + (by - ay) * i / n)) for i in range(n)]
        seq = []
        for x, y in pts:
            if any(a <= x <= c and b <= y <= d for a, b, c, d in ignore):
                seq.append(None)
                continue
            patch = img[max(0, y - r): y + r + 1, max(0, x - r): x + r + 1]
            name, _ = C.dominant(patch, min_fraction=0.35, ignore_black=True)
            seq.append(name)
        # gira in modo che la sequenza non inizi a meta' di un rocchetto
        if seq and seq[0] is not None:
            k = next((i for i, s in enumerate(seq) if s is None), 0)
            seq = seq[k:] + seq[:k]
        # Raggruppa campioni consecutivi dello stesso colore, tollerando piccoli buchi
        # (un rocchetto di traverso ha il centro stretto)
        belt = Counter()
        min_len = max(3, int(0.06 * w) // step)
        max_gap = max(1, int(0.04 * w) // step)
        cur, n, gap = None, 0, 0
        for s in seq + [None] * (max_gap + 1):
            if s is not None and s == cur:
                n += 1
                gap = 0
                continue
            if cur is not None and gap < max_gap and s is None:
                gap += 1
                continue
            if cur is not None and n >= min_len:
                belt[cur] += 1
            cur, n, gap = s, (1 if s is not None else 0), 0
        return belt

    # ------------------------------------------------------------------ tutto
    def analyze(self, img: np.ndarray) -> State:
        img = np.ascontiguousarray(img[..., :3])
        cls = C.classify(img)
        exposed, remaining, probes = self.picture(img, cls)
        return State(
            width=img.shape[1],
            height=img.shape[0],
            exposed=exposed,
            remaining=remaining,
            slots=self.slots(img),
            grid=self.grid(img, cls),
            belt=self.belt(img),
            probes=probes,
        )


def _runs(mask: np.ndarray, min_len: int):
    out, start = [], None
    for i, m in enumerate(list(mask) + [False]):
        if m and start is None:
            start = i
        elif not m and start is not None:
            if i - start >= min_len:
                out.append((start, i))
            start = None
    return out
