"""Genera i modelli delle cifre (yarnloop_bot/digit_templates.py e Digits.java) dagli screenshot di esempio.

Le cifre del gioco sono bianche con il bordo nero. 6 e 8 non compaiono negli esempi:
il 6 e' il 9 ruotato, l'8 viene disegnato con un font simile.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
sys.path.insert(0, ROOT)
from yarnloop_bot.digits import GW, GH, components, normalize, white_mask  # noqa: E402

# (file, x, y) di un pixel dentro la cifra -> carattere
SAMPLES = [
    ("livello31", 77, 1325, "2"), ("livello31", 105, 1325, "/"), ("livello31", 134, 1325, "5"),
    ("livello31", 191, 1515, "7"), ("livello31", 365, 1515, "9"), ("livello31", 540, 1517, "5"),
    ("livello31", 252, 1732, "1"), ("livello31", 292, 1732, "0"), ("livello31", 145, 432, "9"),
    ("livello31", 724, 400, "4"), ("livello33", 77, 1326, "4"), ("livello33", 174, 1516, "1"),
    ("livello33", 365, 1516, "0"), ("livello33", 342, 1731, "4"), ("livello33", 385, 1731, "0"),
    ("livello33", 514, 1730, "1"), ("livello33", 554, 1730, "0"), ("livello33", 364, 1909, "3"),
    ("livello33", 93, 894, "1"), ("livello33", 122, 894, "9"),
    ("livello35_meta", 978, 1025, "6"), ("livello35_bianco20", 966, 975, "8"),
    ("livello35_bianco20", 516, 1730, "2"),  # "2" su un tappo bianco (esce spezzato)
    # scritta "Livello N" in alto (cifre piu' sottili)
    ("livello35_etichetta", 195, 186, "3"), ("livello35_etichetta", 221, 186, "5"),
    ("livello31", 201, 186, "3"), ("livello31", 223, 185, "1"),
]


def from_screens():
    out = []
    cache = {}
    for f, x, y, ch in SAMPLES:
        if f not in cache:
            cache[f] = np.asarray(Image.open(os.path.join(ROOT, "examples", f + ".jpg")).convert("RGB"))
        img = cache[f]
        x0, y0 = x - 40, y - 40
        sub = img[y0:y + 40, x0:x + 40]
        m = white_mask(sub)
        found = None
        for c in components(m, min_px=100):
            cx0, cy0, cx1, cy1, _ = c
            if cx0 <= 40 < cx1 and cy0 <= 40 < cy1 and m[40 - 0, 40 - 0] or (cx0 <= 40 < cx1 and cy0 <= 40 < cy1):
                found = c
        if found is None:
            raise SystemExit(f"cifra non trovata: {f} {x},{y} {ch}")
        cx0, cy0, cx1, cy1, _ = found
        out.append((ch, normalize(m[cy0:cy1, cx0:cx1])))
    return out


def rendered(ch):
    font = ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf", 120)
    im = Image.new("L", (160, 160), 0)
    ImageDraw.Draw(im).text((20, 0), ch, fill=255, font=font)
    m = np.asarray(im) > 128
    ys, xs = np.nonzero(m)
    return normalize(m[ys.min():ys.max() + 1, xs.min():xs.max() + 1])


def main():
    temps = from_screens()
    nines = [t for c, t in temps if c == "9"]
    for t in nines:
        a, v = t
        temps.append(("6", (a, v[::-1, ::-1].copy())))
    temps.append(("8", rendered("8")))
    # controllo: ogni campione deve riconoscersi
    from yarnloop_bot import digits
    digits.TEMPLATES = [(c, a, v) for c, (a, v) in temps]
    for c, (a, v) in temps:
        got, d = digits.classify_features(a, v)
        assert got == c, (c, got, d)

    def enc(v):
        return "".join("0123456789"[min(9, int(round(x * 9)))] for x in v.ravel())

    py = ["# File generato da tools/make_digits.py: non modificare a mano.",
          f"GW, GH = {GW}, {GH}", "TEMPLATES_RAW = ["]
    py += [f'    ("{c}", {a:.3f}, "{enc(v)}"),' for c, (a, v) in temps]
    py.append("]")
    with open(os.path.join(ROOT, "yarnloop_bot", "digit_templates.py"), "w") as f:
        f.write("\n".join(py) + "\n")

    java = ["package com.yarnloop.bot;", "", "/** Generato da tools/make_digits.py: non modificare a mano. */",
            "final class DigitTemplates {", "    static final String[] CHARS = {"]
    java.append("        " + ", ".join(f'"{c}"' for c, _ in temps) + "};")
    java.append("    static final float[] ASPECT = {" + ", ".join(f"{a:.3f}f" for _, (a, _) in temps) + "};")
    java.append("    static final String[] DATA = {")
    java += [f'        "{enc(v)}",' for _, (_, v) in temps]
    java += ["    };", "}"]
    path = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "yarnloop", "bot", "DigitTemplates.java")
    with open(path, "w") as f:
        f.write("\n".join(java) + "\n")
    print(len(temps), "modelli scritti")


if __name__ == "__main__":
    main()
