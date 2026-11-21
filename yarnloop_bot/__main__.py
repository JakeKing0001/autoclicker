"""Autoclicker per Yarn Loop.

Esempi:
  python -m yarnloop_bot run                 # gioca da solo
  python -m yarnloop_bot run --dry-run       # mostra cosa farebbe, senza toccare
  python -m yarnloop_bot analyze foto.png    # analizza uno screenshot e salva il debug
  python -m yarnloop_bot screenshot          # salva uno screenshot del telefono
  python -m yarnloop_bot simple --tap 0.5,0.8 --interval 0.2   # autoclicker classico
"""
from __future__ import annotations

import argparse
import os
import sys
import time

import numpy as np
from PIL import Image

from .config import Config
from . import guide, screens
from .debug import draw
from .strategy import Strategy
from .vision import Analyzer


def _device(args):
    from .adb import ADBError, Device

    try:
        dev = Device(args.serial)
        print("Telefono collegato:", dev.check())
        return dev
    except ADBError as e:
        sys.exit(f"Errore ADB: {e}")


def _fmt(counter):
    return ", ".join(f"{k}={v}" for k, v in counter.most_common()) or "-"


def cmd_run(args):
    cfg = Config.load(args.config)
    dev = _device(args)
    an, strat = Analyzer(cfg), Strategy(cfg)
    if args.save_debug:
        os.makedirs(args.save_debug, exist_ok=True)
    taps, idle, frame = 0, 0, 0
    last_screen, last_screen_tap = None, 0.0
    level_streak = 0
    loader = (lambda n: guide.from_file(args.guide_file)) if args.guide_file else None
    tracker = guide.LevelTracker(args.level, online=cfg.follow_guide, loader=loader)
    print("Avviato. Ctrl+C per fermare.")
    try:
        while args.max_taps is None or taps < args.max_taps:
            img = dev.screenshot()
            arr = np.asarray(img)
            st = an.analyze(arr)
            # numero del livello (Home / Hai perso) e guida di yarnlooplevel.com per quel livello
            kind, pt = screens.classify(arr) if st.belt_free is None else (None, None)
            fresh = st.belt_free is not None and st.belt_free == (st.belt_cap or 5) and st.free_slots == len(st.slots)
            in_level = st.belt_free is not None
            tracker.update(in_level, fresh, kind, screens.level_number(arr, kind, in_level))
            strat.guide = tracker.colors
            dec = strat.decide(st)
            frame += 1
            # il livello deve essere visibile in due screenshot di fila, e non subito dopo un pulsante
            # (dopo Riprova si vede per un attimo la tavola vecchia, poi parte la pubblicita')
            level_streak = level_streak + 1 if st.belt_free is not None else 0
            if dec.moves and (level_streak < 2 or time.monotonic() - last_screen_tap < cfg.settle_time):
                dec.moves, dec.reason = [], "aspetto che il livello sia pronto"
            print(
                f"[{frame}] livello {tracker.level or '?'} guida {strat.guide or tracker.status or '-'} | esposti: {_fmt(st.exposed)} | "
                f"slot liberi {st.free_slots} -> {dec.reason}"
            )
            if args.save_debug:
                draw(img, st, cfg, dec.moves).save(os.path.join(args.save_debug, f"{frame:05d}.jpg"), quality=70)

            if dec.moves:
                idle = 0
                for m in dec.moves:
                    if not args.dry_run:
                        dev.tap(m.spool.x, m.spool.y)
                        strat.remember(m)
                        taps += 1
                    time.sleep(cfg.tap_gap)
                time.sleep(cfg.after_burst)
                continue

            if st.belt_free is None and cfg.auto_buttons:
                # fuori dal livello: Riprova / Continua / X / Livello N, solo se la schermata e' stabile
                if kind and kind == last_screen and time.monotonic() - last_screen_tap > cfg.screen_tap_every:
                    print(f"   {kind}: tocco {pt}")
                    if not args.dry_run:
                        dev.tap(*pt)
                    last_screen_tap = time.monotonic()
                    kind = None
                last_screen = kind
            time.sleep(cfg.idle_delay)
    except KeyboardInterrupt:
        pass
    print(f"Fermato. Tocchi fatti: {taps}")


def cmd_analyze(args):
    cfg = Config.load(args.config)
    img = Image.open(args.image).convert("RGB")
    st = Analyzer(cfg).analyze(np.asarray(img))
    dec = Strategy(cfg).decide(st)
    print("Esposti (raggiungibili dal nastro):", _fmt(st.exposed))
    print("Ancora nel disegno:               ", _fmt(st.remaining))
    print("Sul nastro:                       ", _fmt(st.belt))
    print("Slot:", [s.color if s else "vuoto" for s in st.slots])
    for s in st.grid:
        print(f"Colonna {s.column}: davanti {s.color} {s.count if s.count is not None else ''}, dietro {s.behind or '-'}  (tocco a {s.x},{s.y})")
    print("Posti liberi sul nastro:", st.belt_free)
    if st.belt_free is None:
        kind, pt = screens.classify(np.asarray(img))
        print("Schermata:", kind or "sconosciuta (non tocco niente)", pt or "")
    print("Mosse scelte:", dec.reason)
    for m in dec.moves:
        print(f"   tocco ({m.spool.x},{m.spool.y}) {'+'.join(m.colors)}")
    out = args.output or os.path.splitext(args.image)[0] + "_debug.png"
    draw(img, st, cfg, dec.moves).save(out)
    print("Immagine di debug salvata in", out)


def cmd_screenshot(args):
    dev = _device(args)
    img = dev.screenshot()
    img.save(args.output)
    print(f"Salvato {args.output} ({img.width}x{img.height})")


def cmd_simple(args):
    dev = _device(args)
    w, h = dev.screenshot().size
    pts = []
    for t in args.tap:
        x, y = (float(v) for v in t.split(","))
        # valori <= 1 sono frazioni dello schermo, altrimenti pixel
        pts.append((int(x * w) if x <= 1 else int(x), int(y * h) if y <= 1 else int(y)))
    print("Tocco in", pts, "ogni", args.interval, "s. Ctrl+C per fermare.")
    n = 0
    try:
        while args.count is None or n < args.count:
            for x, y in pts:
                dev.tap(x, y)
                n += 1
                time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    print("Tocchi fatti:", n)


def cmd_config(args):
    Config().dump(args.output)
    print("Configurazione di default scritta in", args.output)


def main(argv=None):
    p = argparse.ArgumentParser(prog="yarnloop_bot", description="Autoclicker per Yarn Loop (via ADB)")
    p.add_argument("--serial", "-s", help="seriale ADB del telefono (se ne hai piu' di uno)")
    p.add_argument("--config", "-c", help="file JSON con la configurazione")
    sub = p.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("run", help="gioca automaticamente")
    r.add_argument("--dry-run", action="store_true", help="non tocca niente, mostra solo le decisioni")
    r.add_argument("--max-taps", type=int, help="fermati dopo N tocchi")
    r.add_argument("--save-debug", metavar="CARTELLA", help="salva ogni fotogramma annotato")
    r.add_argument("--level", type=int, help="livello di partenza (altrimenti lo legge in Home / Hai perso)")
    r.add_argument("--guide-file", help="pagina della guida salvata (.mht/.html) invece di scaricarla")
    r.set_defaults(func=cmd_run)

    a = sub.add_parser("analyze", help="analizza uno screenshot salvato")
    a.add_argument("image")
    a.add_argument("--output", "-o")
    a.set_defaults(func=cmd_analyze)

    s = sub.add_parser("screenshot", help="salva uno screenshot dal telefono")
    s.add_argument("--output", "-o", default="screenshot.png")
    s.set_defaults(func=cmd_screenshot)

    m = sub.add_parser("simple", help="autoclicker classico su punti fissi")
    m.add_argument("--tap", action="append", required=True, metavar="X,Y",
                   help="punto da toccare (frazioni 0-1 o pixel). Ripetibile.")
    m.add_argument("--interval", type=float, default=0.3)
    m.add_argument("--count", type=int)
    m.set_defaults(func=cmd_simple)

    c = sub.add_parser("config", help="scrive la configurazione di default in un file JSON")
    c.add_argument("--output", "-o", default="config.json")
    c.set_defaults(func=cmd_config)

    args = p.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
