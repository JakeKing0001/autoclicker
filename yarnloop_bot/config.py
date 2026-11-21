"""Configurazione: posizioni delle zone dello schermo, in frazioni di larghezza/altezza.

I valori di default sono misurati su uno screenshot 1080x2340 (Livello 31).
Se il tuo telefono ha proporzioni diverse, lancia `python -m yarnloop_bot analyze`
e correggi i valori in un file JSON passato con `--config`.
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field

REF_W, REF_H = 1080, 2340


def _r(x0, y0, x1, y1):
    """Rettangolo in pixel di riferimento -> frazioni."""
    return [round(x0 / REF_W, 4), round(y0 / REF_H, 4), round(x1 / REF_W, 4), round(y1 / REF_H, 4)]


@dataclass
class Config:
    # Zona del disegno (dentro il nastro)
    picture: list = field(default_factory=lambda: _r(170, 385, 910, 1250))
    # Dimensione di un "pixel" di lana del disegno (frazione della larghezza)
    cell: float = round(32 / REF_W, 4)
    # Riga degli slot di attesa: rettangolo + centri x di ogni slot
    slots: list = field(default_factory=lambda: _r(110, 1505, 975, 1650))
    slot_x: list = field(default_factory=lambda: [round(x / REF_W, 4) for x in (190, 365, 540, 715, 890)])
    # Griglia dei rocchetti (esclusa la barra dei bonus in basso!)
    grid: list = field(default_factory=lambda: _r(100, 1680, 980, 2110))
    # Maglia della griglia: centro delle colonne, distanza tra colonne, centro del tappo della 1a riga
    grid_center_x: float = 0.5
    col_pitch: float = round(175 / REF_W, 4)
    first_cap_y: float = round(1735 / REF_H, 4)
    # Pezzi del disegno piu' piccoli di cosi' (in celle) vengono ignorati (numeri, scritte)
    min_part_cells: int = 5
    # Larghezza di un rocchetto (frazione della larghezza)
    spool_w: float = round(125 / REF_W, 4)
    # Distanza verticale tra due rocchetti nella stessa colonna
    spool_pitch: float = round(187 / REF_H, 4)
    # Contatore dei posti liberi sul nastro ("2/5") e altezza delle cifre (frazione della larghezza)
    counter: list = field(default_factory=lambda: _r(40, 1270, 185, 1390))
    digit_h: float = 0.034
    # Percorso del nastro: rettangolo che passa a meta' della corsia
    belt: list = field(default_factory=lambda: _r(105, 290, 975, 1330))
    # Zone del nastro da ignorare (i cancelli arancioni e il contatore "2/5")
    belt_ignore: list = field(default_factory=lambda: [_r(30, 1140, 185, 1395), _r(185, 1250, 290, 1400)])
    # Comportamento
    max_on_belt: int = 5          # non lanciare altri rocchetti se sul nastro ce ne sono gia' tanti
    yarn_per_spool: int = 8       # stima di quanta lana raccoglie un rocchetto sul nastro
    tap_gap: float = 0.12         # pausa tra due tocchi della stessa raffica (secondi)
    after_burst: float = 0.5      # pausa dopo una raffica, prima del prossimo screenshot
    idle_delay: float = 0.6       # attesa quando non c'e' niente da fare
    loop_time: float = 8.0        # quanto tempo un rocchetto resta in giro sul nastro (circa un giro)
    recent_window: float = 2.5    # per quanti secondi un rocchetto appena toccato conta come "sul nastro"
    reserve_slots: int = 1        # slot da lasciare sempre liberi quando si scava
    tap_check: float = 0.45       # dopo quanto controllare se il rocchetto toccato e' partito
    block_time: float = 6.0       # per quanto evitare un rocchetto che non e' partito
    # Fuori dal livello tocca da solo Riprova / Continua / X di chiusura / "Livello N"
    # (mai offerte a pagamento). La schermata deve essere uguale in due screenshot di fila.
    auto_buttons: bool = True
    screen_tap_every: float = 2.5  # secondi minimi tra due tocchi su queste schermate

    @classmethod
    def load(cls, path: str | None) -> "Config":
        cfg = cls()
        if path:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            for k, v in data.items():
                if not hasattr(cfg, k):
                    raise KeyError(f"Chiave di configurazione sconosciuta: {k}")
                setattr(cfg, k, v)
        return cfg

    def dump(self, path: str) -> None:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(asdict(self), f, indent=2)
