"""Immagine di debug con tutto quello che il bot "vede"."""
from __future__ import annotations

from PIL import Image, ImageDraw

from . import colors as C
from .config import Config
from .vision import State, _px


def draw(img: Image.Image, state: State, cfg: Config, choice=None) -> Image.Image:
    im = img.convert("RGB").copy()
    d = ImageDraw.Draw(im)
    w, h = im.size

    def rect(r, col):
        d.rectangle(_px(r, w, h), outline=col, width=4)

    rect(cfg.picture, (0, 255, 255))
    rect(cfg.slots, (255, 0, 255))
    rect(cfg.grid, (255, 255, 0))
    rect(cfg.belt, (0, 255, 0))
    for r in cfg.belt_ignore:
        rect(r, (255, 0, 0))

    for x, y, name in state.probes:
        col = C.DEBUG_RGB.get(name, (255, 255, 255))
        d.ellipse((x - 4, y - 4, x + 4, y + 4), fill=col, outline=(255, 255, 255))

    for s in [s for s in state.slots if s] + state.grid:
        d.ellipse((s.x - 18, s.y - 18, s.x + 18, s.y + 18), fill=C.DEBUG_RGB[s.color], outline=(0, 255, 255), width=4)
        d.text((s.x - 30, s.y + 22), s.color, fill=(255, 255, 255))
        if s.behind:
            d.text((s.x - 30, s.y + 36), "dietro: " + ",".join(s.behind), fill=(255, 255, 255))

    if choice is not None:
        d.ellipse((choice.x - 40, choice.y - 40, choice.x + 40, choice.y + 40), outline=(255, 0, 0), width=8)

    lines = [
        "esposti: " + ", ".join(f"{k}={v}" for k, v in state.exposed.most_common()),
        "nel disegno: " + ", ".join(f"{k}={v}" for k, v in state.remaining.most_common()),
        "nastro: " + ", ".join(f"{k}={v}" for k, v in state.belt.most_common()),
        f"slot liberi: {state.free_slots}/{len(state.slots)}",
    ]
    y = 10
    for line in lines:
        d.rectangle((0, y - 2, w, y + 14), fill=(0, 0, 0))
        d.text((6, y), line, fill=(255, 255, 255))
        y += 18
    return im
