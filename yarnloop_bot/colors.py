"""Classificazione dei colori (solo numpy, nessun OpenCV).

Ogni pixel viene convertito in HSV e assegnato a un nome di colore.
Le classi speciali sono:
  "bg"        -> sfondo grigio del gioco (ignorato)
  "cream"     -> cornici color crema (ignorato)
  "generator" -> distributore grigio-blu nella griglia dei rocchetti
"""
from __future__ import annotations

import numpy as np

IGNORED = {"bg", "cream"}
SPECIAL = {"generator"}

# Colore usato nelle immagini di debug per ogni classe
DEBUG_RGB = {
    "black": (0, 0, 0),
    "white": (255, 255, 255),
    "bg": (90, 90, 90),
    "cream": (250, 235, 180),
    "generator": (100, 110, 165),
    "mystery": (60, 60, 60),
    "red": (230, 20, 20),
    "pink": (255, 150, 150),
    "skin": (235, 160, 110),
    "orange": (255, 140, 0),
    "brown": (110, 55, 10),
    "yellow": (255, 230, 0),
    "lime": (170, 230, 40),
    "green": (0, 150, 0),
    "cyan": (0, 220, 220),
    "lightblue": (120, 190, 255),
    "blue": (30, 60, 230),
    "purple": (140, 60, 220),
    "magenta": (230, 40, 200),
}

CLASSES = list(DEBUG_RGB.keys())
_INDEX = {n: i for i, n in enumerate(CLASSES)}


def rgb_to_hsv(rgb: np.ndarray):
    """rgb: array (..., 3) uint8/int -> (h in gradi, s 0..1, v 0..1)."""
    rgb = rgb.astype(np.float32) / 255.0
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx = rgb.max(axis=-1)
    mn = rgb.min(axis=-1)
    d = mx - mn
    s = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0.0)
    h = np.zeros_like(mx)
    dd = np.maximum(d, 1e-6)
    hr = ((g - b) / dd) % 6
    hg = (b - r) / dd + 2
    hb = (r - g) / dd + 4
    h = np.where(mx == r, hr, np.where(mx == g, hg, hb)) * 60.0
    h = np.where(d < 1e-6, 0.0, h)
    return h, s, mx


_LUT = None


def _lut() -> np.ndarray:
    """Tabella con la classe di tutti i 16,7 milioni di colori (16 MB), calcolata una volta e salvata."""
    global _LUT
    if _LUT is None:
        import hashlib
        import inspect
        import os
        import tempfile
        src = inspect.getsource(_classify_exact).encode()
        tag = hashlib.sha1(src + ",".join(CLASSES).encode()).hexdigest()[:10]
        path = os.path.join(tempfile.gettempdir(), f"yarnloop_colori_{tag}.npy")
        try:
            _LUT = np.load(path)
        except Exception:
            lut = np.empty(1 << 24, dtype=np.uint8)
            step = 1 << 20
            for a in range(0, 1 << 24, step):
                idx = np.arange(a, a + step, dtype=np.uint32)
                rgb = np.stack([(idx >> 16) & 255, (idx >> 8) & 255, idx & 255], axis=-1)
                lut[a:a + step] = _classify_exact(rgb)
            try:
                np.save(path, lut)
            except Exception:
                pass
            _LUT = lut
    return _LUT


def classify(rgb: np.ndarray) -> np.ndarray:
    """Restituisce un array di indici di classe (vedi CLASSES) con shape rgb.shape[:-1]."""
    rgb = np.asarray(rgb)
    if rgb.size == 0:
        return np.zeros(rgb.shape[:-1], dtype=np.uint8)
    r = rgb[..., 0].astype(np.uint32)
    g = rgb[..., 1].astype(np.uint32)
    b = rgb[..., 2].astype(np.uint32)
    return _lut()[(r << 16) | (g << 8) | b]


def _classify_exact(rgb: np.ndarray) -> np.ndarray:
    """Classificazione vera (HSV): serve solo a costruire la tabella."""
    h, s, v = rgb_to_hsv(rgb)
    out = np.full(h.shape, _INDEX["bg"], dtype=np.uint8)

    def put(mask, name):
        nonlocal out
        out = np.where(mask & (out == 255), _INDEX[name], out)

    out[:] = 255  # "non assegnato"
    # Neri: molto scuri, oppure scuri e quasi neutri (lo sfondo ha una tinta rosata)
    put((v < 0.20) | ((v < 0.30) & (s < 0.07)), "black")
    # Grigi / bianchi poco saturi
    low_sat = s < 0.22
    # Lana bianca (anche in ombra): grigio neutro e chiaro. Lo sfondo e' piu' scuro e rosato.
    put((s < 0.12) & (v >= 0.55), "white")
    put((s < 0.33) & (v > 0.70), "cream")  # cornici crema e riflessi chiari
    put(low_sat, "bg")
    # Distributore: blu-grigio spento
    put((h >= 200) & (h < 260) & (s >= 0.22) & (s < 0.5) & (v < 0.78), "generator")

    hh = h
    # Rossi e rosa (stessa tinta, saturazione diversa)
    redish = (hh < 12) | (hh >= 345)
    put(redish & (s >= 0.75), "red")
    put(((hh < 20) | (hh >= 345)) & (s < 0.75), "pink")
    # Arancio / pelle / marrone
    orangish = (hh >= 12) & (hh < 42)
    put(orangish & (v < 0.60), "brown")
    put(orangish & (s >= 0.80), "orange")
    put(orangish, "skin")
    put((hh >= 42) & (hh < 70), "yellow")
    put((hh >= 70) & (hh < 95), "lime")
    put((hh >= 95) & (hh < 165), "green")
    put((hh >= 165) & (hh < 195), "cyan")
    put((hh >= 195) & (hh < 250) & (s < 0.55), "lightblue")
    put((hh >= 195) & (hh < 250), "blue")
    put((hh >= 250) & (hh < 290), "purple")
    put((hh >= 290) & (hh < 345), "magenta")
    put(np.ones_like(low_sat), "bg")
    return out


def name(idx: int) -> str:
    return CLASSES[int(idx)]


def index(n: str) -> int:
    return _INDEX[n]


def dominant(rgb_patch: np.ndarray, min_fraction: float = 0.25, ignore_black: bool = False):
    """Colore dominante (escluse le classi ignorate) di una porzione d'immagine.

    Restituisce (nome, frazione) oppure (None, frazione_sfondo) se il patch e' vuoto.
    """
    cls = classify(rgb_patch).ravel()
    counts = np.bincount(cls, minlength=len(CLASSES)).astype(float)
    total = counts.sum()
    for n in IGNORED:
        counts[_INDEX[n]] = 0
    if ignore_black:
        counts[_INDEX["black"]] = 0
    best = int(counts.argmax())
    frac = counts[best] / max(total, 1)
    if frac < min_fraction:
        return None, frac
    return CLASSES[best], frac


def debug_image(rgb: np.ndarray) -> np.ndarray:
    lut = np.array([DEBUG_RGB[n] for n in CLASSES], dtype=np.uint8)
    return lut[classify(rgb)]
