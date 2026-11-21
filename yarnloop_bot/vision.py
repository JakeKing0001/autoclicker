"""Analisi di uno screenshot di Yarn Loop."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field

import numpy as np

from . import colors as C
from . import digits as D
from .config import Config


@dataclass
class Spool:
    color: str
    x: int          # punto da toccare
    y: int
    where: str      # "slot" o "grid"
    column: int = -1
    behind: list = field(default_factory=list)  # colori dei rocchetti dietro (solo griglia)
    row: int = 0
    index: int = -1                              # posizione nello slot
    count: int | None = None                     # numero scritto sul rocchetto (lana che manca)


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
    # Griglia completa: cells[(colonna, riga)] = colore; posizioni x/y di colonne e righe
    cells: dict = field(default_factory=dict)
    col_x: list = field(default_factory=list)
    row_y: list = field(default_factory=list)
    links: dict = field(default_factory=dict)       # (col, riga) -> (col, riga) del compagno
    slot_links: dict = field(default_factory=dict)  # indice slot -> indice slot collegato
    belt_free: int | None = None                    # posti liberi sul nastro (contatore "N/5")
    belt_cap: int | None = None                     # posti in tutto (il 5 di "N/5")
    belt_spools: list = field(default_factory=list)  # [(colore, lana che manca)] dei rocchetti sul nastro
    counts: dict = field(default_factory=dict)      # (col, riga) -> numero letto (prima fila e distributori)

    def front_row(self, col):
        rows = [r for (c, r) in self.cells if c == col]
        return min(rows) if rows else None

    def tap_point(self, col, row):
        return int(self.col_x[col]), int(self.row_y[row] + 0.05 * (self.row_y[1] - self.row_y[0] if len(self.row_y) > 1 else 0))

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
        fg = _drop_small_parts(fg, cell, self.cfg.min_part_cells)

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
            out.append(Spool(name, cx, (y0 + y1) // 2, "slot", index=len(out)) if name else None)
        return out

    def slot_links(self, img: np.ndarray, slots):
        """Due rocchetti vicini negli slot uniti da una sbarretta."""
        h, w = img.shape[:2]
        _, y0, _, y1 = _px(self.cfg.slots, w, h)
        links = {}
        for i in range(len(slots) - 1):
            a, b = slots[i], slots[i + 1]
            if not a or not b:
                continue
            xm = (a.x + b.x) // 2
            band = img[y0:y1, xm - 4: xm + 5]
            cls = C.classify(band)
            colored = ~np.isin(cls, [C.index(n) for n in C.IGNORED])
            # la sbarretta e' una striscia orizzontale spessa 20-70 px; il bordo rosso che lampeggia
            # quando gli slot sono quasi pieni e' invece una riga verticale lunga quanto lo slot
            full = colored.mean(axis=1) > 0.8
            runs = _runs(full, 1)
            if any(0.008 * h <= (e - s0) <= 0.03 * h for s0, e in runs):
                links[i], links[i + 1] = i + 1, i
        return links

    # ------------------------------------------------------------------ griglia
    def grid_cells(self, img: np.ndarray):
        """Legge la griglia dei rocchetti su una maglia fissa.

        Le colonne sono centrate sullo schermo e distano `col_pitch`; con un numero pari di
        colonne sono sfalsate di mezzo passo, quindi si provano entrambe le possibilita'.
        Restituisce (lista di x delle colonne, matrice colori [riga][colonna]).
        """
        h, w = img.shape[:2]
        _, gy0, _, gy1 = _px(self.cfg.grid, w, h)
        cx0 = self.cfg.grid_center_x * w
        pitch_x = self.cfg.col_pitch * w
        pitch_y = self.cfg.spool_pitch * h
        cap0 = self.cfg.first_cap_y * h
        half = int(self.cfg.spool_w * w * 0.33)
        rows = [cap0 + i * pitch_y for i in range(8) if cap0 + i * pitch_y - 0.25 * pitch_y < gy1]

        def read(x, i):
            y = rows[i]
            if y + 0.5 * pitch_y < gy1:
                # corpo del rocchetto (sotto il tappo, niente numeri)
                ya, yb = int(y + 0.25 * pitch_y), int(y + 0.55 * pitch_y)
            else:
                # ultima riga mezza nascosta dalla barra: striscia in cima al tappo
                ya, yb = int(y - 0.24 * pitch_y), int(y - 0.12 * pitch_y)
            ya, yb = max(gy0, ya), min(gy1, yb)
            if yb - ya < 4:
                return None
            x = int(x)
            name, _ = C.dominant(img[ya:yb, max(0, x - half): x + half], min_fraction=0.3)
            if name == "black" and self._is_mystery(img, x, y, half, pitch_y, gy0, gy1):
                return "mystery"
            return name

        best = None
        for offset in (0.0, 0.5):
            xs = []
            for k in range(-4, 5):
                x = cx0 + (k + offset) * pitch_x
                if pitch_x * 0.4 < x < w - pitch_x * 0.4:
                    xs.append(x)
            xs = sorted(set(xs))
            mat = [[read(x, i) for x in xs] for i in range(len(rows))]
            filled = sum(1 for r in mat[:2] for v in r if v)
            if best is None or filled > best[0]:
                best = (filled, xs, mat)
        _, xs, mat = best
        return xs, rows, mat

    def links(self, img: np.ndarray, xs, rows, cells):
        """Trova le aste che collegano due rocchetti di colonne vicine.

        Si controllano tre punti lungo la retta tra i due rocchetti, nello spazio vuoto tra
        le colonne (dove normalmente c'e' solo sfondo). Il compagno puo' anche essere in una
        riga nascosta dalla barra in basso: in quel caso il suo colore e' sconosciuto (None).
        """
        h, w = img.shape[:2]
        pitch_y = self.cfg.spool_pitch * h
        r = max(3, int(0.005 * w))
        gy1 = _px(self.cfg.grid, w, h)[3]
        ign = [C.index(n) for n in ("bg", "cream", "black")]
        n_rows = len(rows) + 2  # anche un paio di righe nascoste
        row_y = [rows[0] + i * pitch_y for i in range(n_rows)]
        cand = []
        for c in range(len(xs) - 1):
            for ra in range(n_rows):
                for rb in range(max(0, ra - 3), min(n_rows, ra + 4)):
                    a, b = (c, ra), (c + 1, rb)
                    # almeno uno dei due deve essere visibile
                    if a not in cells and b not in cells:
                        continue
                    if (a not in cells and ra < len(rows)) or (b not in cells and rb < len(rows)):
                        continue  # un lato e' una casella vuota visibile
                    ax, ay = xs[c], row_y[ra] + 0.3 * pitch_y
                    bx, by = xs[c + 1], row_y[rb] + 0.3 * pitch_y
                    vals, pts = [], []
                    for t in (0.4, 0.5, 0.6):
                        x, y = int(ax + (bx - ax) * t), int(ay + (by - ay) * t)
                        if y + r >= gy1:
                            continue
                        patch = C.classify(img[y - r: y + r + 1, x - r: x + r + 1])
                        vals.append(float((~np.isin(patch, ign)).mean()))
                        pts.append((x, y))
                    if len(vals) >= 2 and min(vals) > 0.75:
                        visible = (a in cells) + (b in cells)
                        cand.append((visible, len(vals), min(vals), a, b, pts))
        links, used = {}, []
        near = 0.025 * w
        for _, _, _, a, b, pts in sorted(cand, key=lambda t: t[:3], reverse=True):
            if a in links or b in links:
                continue
            # due aste non passano negli stessi punti: se si sovrappone a una gia' trovata, e' un falso
            if any(abs(x - u) < near and abs(y - v) < near for x, y in pts for u, v in used):
                continue
            links[a], links[b] = b, a
            used += pts
        return links

    @staticmethod
    def _is_mystery(img, x, y, half, pitch_y, gy0, gy1):
        """Rocchetto misterioso: nero con un "?" colorato sul tappo (i neri veri hanno numeri bianchi)."""
        ya, yb = max(gy0, int(y - 0.2 * pitch_y)), min(gy1, int(y + 0.15 * pitch_y))
        if yb - ya < 4:
            return False
        cl = C.classify(img[ya:yb, max(0, x - half): x + half])
        colored = ~np.isin(cl, [C.index(n) for n in ("black", "bg", "cream", "white", "generator")])
        return colored.mean() > 0.03

    def grid(self, img: np.ndarray, cls: np.ndarray = None):
        xs, rows, mat = self.grid_cells(img)
        self._last_grid = (xs, rows, mat)
        result = []
        for ci, x in enumerate(xs):
            stack = []
            first = None
            for i in range(len(rows)):
                v = mat[i][ci]
                if v is None:
                    if stack:
                        break
                    continue
                if first is None:
                    first = i
                stack.append(v)
            if not stack or stack[0] in ("generator", "mystery"):
                continue
            tap_y = int(rows[first] + 0.05 * self.cfg.spool_pitch * img.shape[0])
            result.append(Spool(stack[0], int(x), tap_y, "grid", ci, stack[1:], row=first))
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
        rot = 0
        if seq and seq[0] is not None:
            k = next((i for i, s in enumerate(seq) if s is None), 0)
            seq = seq[k:] + seq[:k]
            rot = k
        # Raggruppa campioni consecutivi dello stesso colore, tollerando piccoli buchi
        # (un rocchetto di traverso ha il centro stretto)
        belt = Counter()
        spools = []  # (colore, x, y) al centro di ogni rocchetto visto sul nastro
        min_len = max(3, int(0.06 * w) // step)
        max_gap = max(1, int(0.04 * w) // step)
        cur, n, gap, start = None, 0, 0, 0
        tail = [None] * (max_gap + 1)
        for i, s in enumerate(seq + tail):
            if s is not None and s == cur:
                n += 1
                gap = 0
                continue
            if cur is not None and gap < max_gap and s is None:
                gap += 1
                continue
            if cur is not None and n >= min_len:
                belt[cur] += 1
                mid = (start + i - gap) // 2
                px, py = pts[(mid + rot) % len(pts)]
                spools.append((cur, px, py))
            cur, n, gap, start = s, (1 if s is not None else 0), 0, i
        self._belt_spools = spools
        return belt

    def belt_labels(self, img: np.ndarray, spools):
        """Numeri accanto ai rocchetti sul nastro = lana che manca a ciascuno.

        Ogni numero viene abbinato al rocchetto colorato piu' vicino; i numeri senza rocchetto
        sono di rocchetti neri (sul nastro scuro non si vedono). Restituisce [(colore, numero)].
        """
        h, w = img.shape[:2]
        x0, y0, x1, y1 = _px(self.cfg.belt, w, h)
        pad, inner = int(0.12 * w), int(0.13 * w)
        # sopra il nastro c'e' la scritta "Livello N": li' si guarda poco
        X0, Y0, X1, Y1 = max(0, x0 - pad), max(0, y0 - int(0.03 * w)), min(w, x1 + pad), min(h, y1 + pad)
        sub = img[Y0:Y1, X0:X1].copy()
        # tieni solo la fascia attorno al nastro (dentro c'e' il disegno, fuori l'interfaccia)
        sub[(y0 + inner - Y0):(y1 - inner - Y0), (x0 + inner - X0):(x1 - inner - X0)] = 0
        cx0, cy0, cx1, cy1 = _px(self.cfg.counter, w, h)
        sub[max(0, cy0 - Y0):max(0, cy1 - Y0), max(0, cx0 - X0):max(0, cx1 - X0)] = 0
        labels = [(x + X0, y + Y0, v) for x, y, v in D.find(sub, self.cfg.digit_h * w)]
        self._belt_label_pos = labels
        pairs = sorted(((np.hypot(lx - sx, ly - sy), li, si) for li, (lx, ly, _) in enumerate(labels)
                        for si, (_, sx, sy) in enumerate(spools)))
        used_l, used_s, out = set(), set(), []
        for dist, li, si in pairs:
            if dist > 0.17 * w or li in used_l or si in used_s:
                continue
            used_l.add(li)
            used_s.add(si)
            out.append((spools[si][0], labels[li][2]))
        for li, (_, _, v) in enumerate(labels):
            if li not in used_l:
                out.append(("black", v))
        return out

    # ------------------------------------------------------------------ tutto
    def analyze(self, img: np.ndarray) -> State:
        img = np.ascontiguousarray(img[..., :3])
        cls = C.classify(img)
        exposed, remaining, probes = self.picture(img, cls)
        slots = self.slots(img)
        grid = self.grid(img, cls)
        xs, rows, mat = self._last_grid
        cells = {(c, r): mat[r][c] for r in range(len(rows)) for c in range(len(xs)) if mat[r][c]}
        belt = self.belt(img)
        h, w = img.shape[:2]
        dh = self.cfg.digit_h * w
        cx0, cy0, cx1, cy1 = _px(self.cfg.counter, w, h)
        txt = D.read(img[cy0:cy1, cx0:cx1], dh)
        parts = txt.split("/")
        belt_free = int(parts[0]) if parts[0].isdigit() else None
        belt_cap = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else None
        sx0, sy0, sx1, sy1 = _px(self.cfg.slots, w, h)
        hw = int(self.cfg.spool_w * w / 2)
        for sp in slots:
            if sp:
                sp.count = D.read_int(img[int(sy0 - 0.017 * h): int(sy0 + 0.034 * h), sp.x - hw: sp.x + hw], dh)
        counts = {}
        pitch = self.cfg.spool_pitch * h
        for (c, r), col in cells.items():
            front = min(rr for (cc, rr) in cells if cc == c) == r
            if front or col == "generator":
                y, half_h = int(rows[r]), int(0.27 * pitch)
                n = D.read_int(img[max(0, y - half_h): y + half_h, int(xs[c]) - hw: int(xs[c]) + hw], dh)
                if n is not None and (n > 0 or col == "generator"):  # un rocchetto in griglia non e' mai a 0
                    counts[(c, r)] = n
        for g in grid:
            g.count = counts.get((g.column, g.row))
        return State(
            width=img.shape[1],
            height=img.shape[0],
            exposed=exposed,
            remaining=remaining,
            slots=slots,
            grid=grid,
            belt=belt,
            belt_spools=self.belt_labels(img, self._belt_spools) if belt_free is not None else [],
            probes=probes,
            cells=cells,
            col_x=xs,
            row_y=rows,
            links=self.links(img, xs, rows, cells),
            slot_links=self.slot_links(img, slots),
            belt_free=belt_free,
            belt_cap=belt_cap,
            counts=counts,
        )


def _drop_small_parts(fg: np.ndarray, cell: int, min_cells: int) -> np.ndarray:
    """Toglie i pezzetti staccati dal disegno (es. i numeri bianchi dei rocchetti sul nastro)."""
    H, W = fg.shape
    gh, gw = (H + cell - 1) // cell, (W + cell - 1) // cell
    pad = np.zeros((gh * cell, gw * cell), bool)
    pad[:H, :W] = fg
    occ = pad.reshape(gh, cell, gw, cell).mean(axis=(1, 3)) > 0.3
    keep = np.zeros_like(occ)
    seen = np.zeros_like(occ)
    for sy in range(gh):
        for sx in range(gw):
            if not occ[sy, sx] or seen[sy, sx]:
                continue
            comp, todo = [], [(sy, sx)]
            seen[sy, sx] = True
            while todo:
                y, x = todo.pop()
                comp.append((y, x))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        ny, nx = y + dy, x + dx
                        if 0 <= ny < gh and 0 <= nx < gw and occ[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            todo.append((ny, nx))
            if len(comp) >= min_cells:
                for y, x in comp:
                    keep[y, x] = True
    mask = np.repeat(np.repeat(keep, cell, axis=0), cell, axis=1)[:H, :W]
    return fg & mask


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
