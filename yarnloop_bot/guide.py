"""Guida di yarnlooplevel.com: ordine dei colori consigliato per ogni livello.

La pagina di un livello (https://yarnlooplevel.com/level/N/) ha una sezione "Full Solution"
scritta a parole, per esempio:
    1. Start with the blue wave strip and the widest white or gray sky sections ...
    3. Bring in brown hull cleanup next ...
Da li' si ricava una priorita' per colore: i colori dei primi passi valgono di piu'.
Il bot la usa come preferenza: decide sempre lui in base a cosa si vede sullo schermo.
"""
from __future__ import annotations

import email
import html as htmlmod
import re
import urllib.request
from email import policy

URL = "https://yarnlooplevel.com/level/{n}/"

# parola inglese -> classi di colore del bot
COLOR_WORDS = {
    "white": ["white"], "cream": ["white"], "black": ["black"], "brown": ["brown"],
    "blue": ["blue", "lightblue"], "navy": ["blue"], "cyan": ["cyan"], "teal": ["cyan"],
    "turquoise": ["cyan"], "yellow": ["yellow"], "gold": ["yellow"], "orange": ["orange"],
    "tan": ["skin"], "beige": ["skin"], "peach": ["skin"], "skin": ["skin"],
    "pink": ["pink", "magenta"], "magenta": ["magenta"], "red": ["red"],
    "green": ["green", "lime"], "lime": ["lime"], "purple": ["purple"], "violet": ["purple"],
}
STEP_WEIGHTS = [6, 4, 2, 1]


def html_to_text(page: str) -> str:
    page = re.sub(r"(?is)<(script|style).*?</\1>", "", page)
    page = re.sub(r"(?i)<br\s*/?>|</p>|</h\d>|</li>|</div>", "\n", page)
    page = re.sub(r"(?s)<[^>]+>", "", page)
    page = htmlmod.unescape(page)
    return re.sub(r"\n\s*\n+", "\n", page)


def solution_steps(text: str) -> list[str]:
    """Le righe della sezione "Full Solution" (vuota se la pagina non ce l'ha)."""
    m = re.search(r"Full Solution(.*?)(Common Mistakes|Frequently Asked|$)", text, re.S)
    if not m:
        return []
    return [ln.strip() for ln in m.group(1).split("\n") if len(ln.strip()) > 15]


def priorities(steps: list[str]) -> dict[str, int]:
    """Colore -> peso (piu' alto = da fare prima secondo la guida)."""
    out: dict[str, int] = {}
    for i, step in enumerate(steps):
        w = STEP_WEIGHTS[i] if i < len(STEP_WEIGHTS) else 0
        low = step.lower()
        if w == 0 or re.search(r"\b(later|last|save)\b", low):
            continue  # "Save most tiny ... for later": non e' una priorita'
        for word, classes in COLOR_WORDS.items():
            if re.search(r"\b" + word + r"\b", low):
                for c in classes:
                    out[c] = max(out.get(c, 0), w)
    return out


def from_html(page: str) -> dict[str, int]:
    return priorities(solution_steps(html_to_text(page)))


def from_file(path: str) -> dict[str, int]:
    """Pagina salvata dal browser (.mht) oppure .html."""
    raw = open(path, "rb").read()
    if path.lower().endswith((".mht", ".mhtml")):
        msg = email.message_from_bytes(raw, policy=policy.default)
        pages = [p.get_payload(decode=True) for p in msg.walk() if p.get_content_type() == "text/html"]
        raw = max(pages, key=len) if pages else b""
    return from_html(raw.decode("utf-8", "replace"))


def fetch(level: int, timeout: float = 10) -> dict[str, int]:
    req = urllib.request.Request(URL.format(n=level), headers={"User-Agent": "Mozilla/5.0 (Android)"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return from_html(r.read().decode("utf-8", "replace"))


class LevelTracker:
    """Tiene il numero del livello e la priorita' dei colori presa dalla guida.

    Il numero si legge in Home ("Livello N") e sul pannello "Hai perso". Se si entra in un livello
    nuovo (nastro vuoto, slot vuoti) senza aver perso ne' essere passati dalla Home, e' il successivo.
    """

    def __init__(self, level: int | None = None, online: bool = True, loader=None):
        self.level = level
        self.online = online
        self.loader = loader or fetch
        self.cache: dict[int, dict[str, int]] = {}
        self.out_of_level = False
        self.lost = False
        self.from_screen = False

    @property
    def colors(self) -> dict[str, int]:
        if not self.online or not self.level:
            return {}
        if self.level not in self.cache:
            try:
                self.cache[self.level] = self.loader(self.level)
            except Exception:
                self.cache[self.level] = {}  # niente rete o pagina diversa: si gioca senza guida
        return self.cache[self.level]

    def update(self, in_level: bool, fresh_level: bool, screen_kind: str | None, number: int | None):
        if number:
            self.level = number
            self.from_screen = True
            if screen_kind and screen_kind.startswith("pulsante"):
                self.lost = True  # "Hai perso": col Riprova si rigioca lo stesso livello
        if not in_level:
            self.out_of_level = True
            return
        if self.out_of_level and fresh_level:
            if self.level and not self.lost and not self.from_screen:
                self.level += 1  # vinto e passato al successivo
            self.lost = self.from_screen = False
        if in_level:
            self.out_of_level = False
