"""Lettura dei numeri del gioco (cifre bianche col bordo nero)."""
from __future__ import annotations

from collections import deque

import numpy as np

GW, GH = 10, 14  # griglia su cui si normalizza ogni cifra

TEMPLATES = None  # [(carattere, aspetto, matrice GHxGW)]


def _load():
    global TEMPLATES
    if TEMPLATES is None:
        from .digit_templates import TEMPLATES_RAW
        TEMPLATES = [(c, a, np.array([int(ch) / 9 for ch in s], dtype=float).reshape(GH, GW))
                     for c, a, s in TEMPLATES_RAW]
    return TEMPLATES


def white_mask(rgb: np.ndarray) -> np.ndarray:
    rgb = rgb.astype(int)
    mn, mx = rgb.min(axis=-1), rgb.max(axis=-1)
    return (mn >= 200) & (mx - mn < 45)


def components(mask: np.ndarray, min_px: int = 40):
    """Componenti connesse (4-vicini): lista di (x0, y0, x1, y1, pixel)."""
    H, W = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    out = []
    for y, x in zip(*np.nonzero(mask)):
        if seen[y, x]:
            continue
        q = deque([(y, x)])
        seen[y, x] = True
        x0 = x1 = x
        y0 = y1 = y
        n = 0
        while q:
            a, b = q.popleft()
            n += 1
            x0, x1, y0, y1 = min(x0, b), max(x1, b), min(y0, a), max(y1, a)
            for na, nb in ((a + 1, b), (a - 1, b), (a, b + 1), (a, b - 1)):
                if 0 <= na < H and 0 <= nb < W and mask[na, nb] and not seen[na, nb]:
                    seen[na, nb] = True
                    q.append((na, nb))
        if n >= min_px:
            out.append((int(x0), int(y0), int(x1) + 1, int(y1) + 1, n))
    return out


def normalize(m: np.ndarray):
    """Maschera della cifra -> (larghezza/altezza, copertura su griglia GHxGW)."""
    h, w = m.shape
    v = np.zeros((GH, GW))
    for gy in range(GH):
        for gx in range(GW):
            ya, yb = gy * h // GH, max(gy * h // GH + 1, (gy + 1) * h // GH)
            xa, xb = gx * w // GW, max(gx * w // GW + 1, (gx + 1) * w // GW)
            v[gy, gx] = m[ya:yb, xa:xb].mean()
    return w / h, v


def classify_features(aspect, v):
    best, bd = None, 9.0
    for c, a, t in _load():
        d = np.abs(v - t).mean() + 0.5 * abs(aspect - a)
        if d < bd:
            best, bd = c, d
    return best, bd


def read(rgb: np.ndarray, digit_h: float, max_dist: float = 0.22) -> str:
    """Legge i caratteri bianchi alti circa `digit_h` pixel nella porzione d'immagine, da sinistra."""
    m = white_mask(rgb)
    found = []
    for x0, y0, x1, y1, n in components(m, min_px=int(digit_h * digit_h * 0.08)):
        h, w = y1 - y0, x1 - x0
        if not (0.75 * digit_h <= h <= 1.3 * digit_h) or w > 1.4 * h:
            continue
        c, d = classify_features(*normalize(m[y0:y1, x0:x1]))
        if d <= max_dist:
            found.append((x0, (y0 + y1) / 2, c))
    if not found:
        return ""
    # tieni la riga piu' numerosa (stessa altezza)
    found.sort()
    ymid = np.median([f[1] for f in found])
    return "".join(c for x, y, c in found if abs(y - ymid) < 0.5 * digit_h)


def read_int(rgb: np.ndarray, digit_h: float):
    s = read(rgb, digit_h)
    s = s.split("/")[0]
    return int(s) if s.isdigit() else None


def find(rgb: np.ndarray, digit_h: float, max_dist: float = 0.2):
    """Tutti i numeri bianchi nell'immagine: lista di (x, y, valore) con x, y al centro del numero."""
    m = white_mask(rgb)
    found = []
    for x0, y0, x1, y1, n in components(m, min_px=int(digit_h * digit_h * 0.08)):
        h, w = y1 - y0, x1 - x0
        if not (0.75 * digit_h <= h <= 1.3 * digit_h) or w > 1.4 * h:
            continue
        c, d = classify_features(*normalize(m[y0:y1, x0:x1]))
        if d <= max_dist and c.isdigit():
            found.append([x0, x1, (y0 + y1) / 2, c])
    found.sort()
    out = []
    for f in found:
        # la cifra continua un numero gia' iniziato sulla stessa riga, subito a sinistra?
        for g in out:
            if abs(f[2] - g[2]) < 0.4 * digit_h and -0.1 * digit_h <= f[0] - g[1] < 0.5 * digit_h and len(g[3]) < 2:
                g[1], g[3] = f[1], g[3] + f[3]
                break
        else:
            out.append(list(f))
    return [((a + b) / 2, y, int(t)) for a, b, y, t in out]
