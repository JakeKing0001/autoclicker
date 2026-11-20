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
    # Larghezza di un rocchetto (frazione della larghezza)
    spool_w: float = round(125 / REF_W, 4)
    # Distanza verticale tra due rocchetti nella stessa colonna
    spool_pitch: float = round(190 / REF_H, 4)
    # Percorso del nastro: rettangolo che passa a meta' della corsia
    belt: list = field(default_factory=lambda: _r(105, 290, 975, 1330))
    # Zone del nastro da ignorare (i cancelli arancioni e il contatore "2/5")
    belt_ignore: list = field(default_factory=lambda: [_r(30, 1140, 185, 1395), _r(185, 1250, 290, 1400)])
    # Comportamento
    max_on_belt: int = 5          # non lanciare altri rocchetti se sul nastro ce ne sono gia' tanti
    yarn_per_spool: int = 8       # stima di quanta lana raccoglie un rocchetto sul nastro
    tap_delay: float = 0.7        # attesa dopo ogni tocco (secondi)
    idle_delay: float = 0.6       # attesa quando non c'e' niente da fare
    recent_window: float = 2.5    # per quanti secondi un rocchetto appena toccato conta come "sul nastro"
    min_free_slots_for_unblock: int = 2  # slot liberi minimi per tentare mosse "di sblocco"
    # Tocco da fare quando non si vede nessun livello (es. pulsante "Continua"). null = disattivato
    idle_tap: list | None = None
    idle_tap_after: int = 6       # cicli vuoti prima di usare idle_tap

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
