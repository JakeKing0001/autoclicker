"""Riconosce le schermate fuori dal livello e dice dove toccare (solo pulsanti sicuri).

Schermate gestite (misurate su 1080x2340):
- "Hai perso!" / "Acquisto annullato" / altri pannelli con un solo pulsante verde in basso
  (Riprova, Continua) e nessuna offerta a pagamento -> tocca il pulsante verde;
- pannelli con la X rossa in alto a destra ("Vuoi davvero procedere?", offerte) -> tocca la X;
- schermata Home con il pulsante "Livello N" -> tocca il pulsante.

Non tocca mai: offerte a pagamento (EUR), "Continua a giocare" con le monete, pubblicita',
finestre di Google Play. Se non riconosce la schermata non fa niente.
"""
from __future__ import annotations

import numpy as np

from . import colors as C
from . import digits as D


def _frac(img, rect, names, w, h):
    x0, y0, x1, y1 = (int(rect[0] * w), int(rect[1] * h), int(rect[2] * w), int(rect[3] * h))
    cl = C.classify(img[y0:y1, x0:x1])
    return float(np.isin(cl, [C.index(n) for n in names]).mean()) if cl.size else 0.0


def _r(x0, y0, x1, y1):
    return (x0 / 1080, y0 / 2340, x1 / 1080, y1 / 2340)


GREEN = ["green", "lime"]
RESULT_BUTTON = _r(380, 1340, 700, 1420)
RESULT_PANEL = _r(150, 650, 930, 1120)
OFFER_BOX = _r(100, 1550, 980, 2050)
CLOSE_AREA = _r(900, 400, 1070, 620)
HOME_BUTTON = _r(350, 1900, 730, 2000)
HOME_NAV = _r(0, 2230, 1080, 2330)


def _is_x(white: np.ndarray, mx: int, my: int, bw: int) -> bool:
    """Una X: bianco lungo le due diagonali, niente bianco sugli assi (a differenza di %, scritte...)."""
    H, W = white.shape

    def at(x, y):
        return 0 <= y < H and 0 <= x < W and bool(white[int(y), int(x)])

    steps = [k / 10 for k in range(-2, 3)]  # da -0.2 a +0.2 del diametro
    d1 = sum(at(mx + t * bw, my + t * bw) for t in steps) / len(steps)
    d2 = sum(at(mx + t * bw, my - t * bw) for t in steps) / len(steps)
    o = 0.22 * bw
    axes = sum(at(mx + dx, my + dy) for dx, dy in ((o, 0), (-o, 0), (0, o), (0, -o))) / 4
    return d1 >= 0.6 and d2 >= 0.6 and axes <= 0.25


def classify(img: np.ndarray):
    """Restituisce (nome, (x, y) da toccare) oppure (None, None)."""
    h, w = img.shape[:2]
    green = _frac(img, RESULT_BUTTON, GREEN, w, h)
    panel = _frac(img, RESULT_PANEL, ["cream", "white"], w, h)
    offer = _frac(img, OFFER_BOX, ["orange", "yellow"], w, h)
    if green >= 0.5 and panel >= 0.6 and offer < 0.05:
        return "pulsante Riprova/Continua", (int(540 / 1080 * w), int(1380 / 2340 * h))

    # Home prima di tutto (le sue icone rosse in alto a destra sono offerte, non X di chiusura)
    if _frac(img, HOME_BUTTON, GREEN, w, h) >= 0.6 and _frac(img, HOME_NAV, ["blue", "lightblue", "purple"], w, h) >= 0.5:
        return "Home: avvio il livello", (int(540 / 1080 * w), int(1950 / 2340 * h))

    # X rossa di chiusura in alto a destra: cerchio rosso con una X bianca in mezzo
    x0, y0, x1, y1 = (int(CLOSE_AREA[0] * w), int(CLOSE_AREA[1] * h), int(CLOSE_AREA[2] * w), int(CLOSE_AREA[3] * h))
    sub = img[y0:y1, x0:x1]
    red = C.classify(sub) == C.index("red")
    white = D.white_mask(sub)
    for cx0, cy0, cx1, cy1, n in D.components(red, min_px=int((0.02 * w) ** 2)):
        bw, bh = cx1 - cx0, cy1 - cy0
        if not (0.05 * w <= bw <= 0.12 * w and 0.75 <= bw / bh <= 1.33):
            continue
        mx, my = (cx0 + cx1) // 2, (cy0 + cy1) // 2
        if _is_x(white, mx, my, bw):
            return "chiudo (X)", (x0 + mx, y0 + my)

    return None, None


LEVEL_LABEL = _r(0, 110, 320, 260)      # scritta "Livello N" in alto a sinistra durante il livello
LEVEL_HOME = _r(330, 1880, 760, 2030)   # pulsante "Livello N" della Home
LEVEL_PANEL = _r(200, 420, 880, 570)    # titolo "Livello N" del pannello Hai perso


def level_number(img: np.ndarray, kind: str | None, in_level: bool = False):
    """Numero del livello: scritta in alto durante il livello, pulsante della Home, titolo del pannello."""
    if in_level:
        rc = LEVEL_LABEL
    elif not kind:
        return None
    elif kind.startswith("Home"):
        rc = LEVEL_HOME
    elif kind.startswith("pulsante"):
        rc = LEVEL_PANEL
    else:
        return None
    h, w = img.shape[:2]
    x0, y0, x1, y1 = int(rc[0] * w), int(rc[1] * h), int(rc[2] * w), int(rc[3] * h)
    return D.read_level(img[y0:y1, x0:x1])
