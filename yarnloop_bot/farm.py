"""Ciclo per farmare livelli dal PC (telefono collegato con ADB).

Oltre a giocare il livello:
- tocca Riprova / Continua / X dei popup / "Livello N" in Home (mai offerte o monete);
- "Vite finite": aspetta e ogni tanto chiude il pannello per vedere se le vite sono tornate;
- schermata sconosciuta ferma (pubblicita', fine livello mai vista...): tasto Indietro;
- se il gioco non e' piu' in primo piano (una pubblicita' ha aperto il Play Store): Indietro,
  poi riapre il gioco;
- tiene un registro delle partite (logs/partite.csv) e salva gli ultimi fotogrammi quando perde.
"""
from __future__ import annotations

import csv
import io
import os
import time
from collections import deque
from datetime import datetime

import numpy as np

from . import guide, screens
from .debug import draw
from .strategy import Strategy
from .vision import Analyzer


def _fmt(counter):
    return ", ".join(f"{k}={v}" for k, v in counter.most_common()) or "-"


class Farmer:
    def __init__(self, dev, cfg, args):
        self.dev, self.cfg, self.args = dev, cfg, args
        self.an, self.strat = Analyzer(cfg), Strategy(cfg)
        loader = (lambda n: guide.from_file(args.guide_file)) if getattr(args, "guide_file", None) else None
        self.tracker = guide.LevelTracker(getattr(args, "level", None), online=cfg.follow_guide, loader=loader)
        self.package = getattr(args, "package", None)
        self.dry = getattr(args, "dry_run", False)
        self.log_dir = None if getattr(args, "no_log", False) else os.path.abspath("logs")
        self.frames = deque(maxlen=cfg.keep_frames)  # (jpeg, riga di stato)
        self.taps = self.frame = 0
        self.wins = self.losses = 0
        # stato delle schermate fuori dal livello
        self.last_screen, self.last_screen_tap = None, 0.0
        self.level_streak = 0
        self.unknown_since = None
        self.last_back = 0.0
        self.lives_since = None
        self.away_since = None
        self.last_fg_check = 0.0
        # partita in corso
        self.playing = None      # numero del livello che si sta giocando
        self.play_start = 0.0
        self.play_taps = 0
        self.loss_logged = False

    # ------------------------------------------------------------------ registro
    def _log(self, level, result):
        if not self.log_dir or not level:
            return
        os.makedirs(self.log_dir, exist_ok=True)
        path = os.path.join(self.log_dir, "partite.csv")
        new = not os.path.exists(path)
        with open(path, "a", newline="", encoding="utf-8") as f:
            w = csv.writer(f, delimiter=";")
            if new:
                w.writerow(["data", "livello", "esito", "durata_s", "tocchi"])
            w.writerow([datetime.now().strftime("%Y-%m-%d %H:%M:%S"), level, result,
                        int(time.monotonic() - self.play_start), self.play_taps])
        if result == "perso" and self.frames:
            d = os.path.join(self.log_dir, f"perso_liv{level}_{datetime.now():%Y%m%d_%H%M%S}")
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, "stato.txt"), "w", encoding="utf-8") as f:
                for i, (jpg, line) in enumerate(self.frames):
                    with open(os.path.join(d, f"{i:03d}.jpg"), "wb") as g:
                        g.write(jpg)
                    f.write(f"{i:03d}: {line}\n")
            print(f"   fotogrammi della partita persa salvati in {d}")

    def _result(self, result):
        if self.playing is None:
            return
        if result == "vinto":
            self.wins += 1
        else:
            self.losses += 1
        print(f"*** Livello {self.playing}: {result.upper()} (vinti {self.wins}, persi {self.losses})")
        self._log(self.playing, result)
        self.playing = None

    def _keep_frame(self, img, line, st=None, moves=None):
        if self.cfg.keep_frames <= 0:
            return
        im = draw(img, st, self.cfg, moves) if (st is not None and self.cfg.log_debug_frames) else img
        im = im.resize((im.width // 2, im.height // 2))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=60)
        self.frames.append((buf.getvalue(), line))

    # ------------------------------------------------------------------ gioco in primo piano
    def _check_foreground(self, in_level):
        now = time.monotonic()
        if in_level or now - self.last_fg_check < self.cfg.fg_check_every:
            return True
        self.last_fg_check = now
        fg = self.dev.foreground()
        if fg is None:
            return True
        if self.package is None:
            self.package = fg  # il primo pacchetto visto con il bot avviato e' il gioco
            print(f"   gioco: {fg}")
            return True
        if fg == self.package:
            self.away_since = None
            return True
        if self.away_since is None:
            self.away_since = now
        away = now - self.away_since
        print(f"   il gioco non e' in primo piano ({fg}) da {away:.0f} s")
        if self.dry:
            return False
        if away > self.cfg.relaunch_after:
            print("   riapro il gioco")
            self.dev.launch(self.package)
            self.away_since = now
        else:
            self.dev.back()
        return False

    # ------------------------------------------------------------------ un passo
    def step(self):
        cfg = self.cfg
        t0 = time.monotonic()
        img = self.dev.screenshot()
        arr = np.asarray(img)
        st = self.an.analyze(arr)
        in_level = st.belt_free is not None
        kind, pt = (None, None) if in_level else screens.classify(arr)
        fresh = in_level and st.belt_free == (st.belt_cap or 5) and st.free_slots == len(st.slots)
        number = screens.level_number(arr, kind, in_level)
        before = self.tracker.level
        self.tracker.update(in_level, fresh, kind, number)
        self.frame += 1

        # --- esito della partita
        if in_level and self.playing is not None and self.tracker.level and before \
                and self.tracker.level > before and before == self.playing:
            self._result("vinto")
        if kind and kind.startswith("pulsante") and number and not self.loss_logged:
            self._result("perso")
            self.loss_logged = True
        if in_level and self.playing is None and fresh:
            self.playing, self.play_start, self.play_taps = self.tracker.level, time.monotonic(), 0
            self.loss_logged = False
            print(f"*** Inizio livello {self.playing or '?'}")

        if not self._check_foreground(in_level):
            time.sleep(cfg.idle_delay)
            return

        # --- dentro il livello
        if in_level:
            self.unknown_since = self.lives_since = None
            self.strat.guide = self.tracker.colors
            dec = self.strat.decide(st)
            self.level_streak += 1
            if dec.moves and (self.level_streak < 2 or time.monotonic() - self.last_screen_tap < cfg.settle_time):
                dec.moves, dec.reason = [], "aspetto che il livello sia pronto"
            line = (f"[{self.frame}] liv.{self.tracker.level or '?'} guida {self.strat.guide or self.tracker.status or '-'}"
                    f" | nastro {st.belt_free}/{st.belt_cap} slot liberi {st.free_slots} -> {dec.reason}")
            print(line + f"  ({time.monotonic() - t0:.2f}s)")
            self._keep_frame(img, line, st, dec.moves)
            if self.args.save_debug:
                draw(img, st, cfg, dec.moves).save(os.path.join(self.args.save_debug, f"{self.frame:05d}.jpg"), quality=70)
            for m in dec.moves:
                if not self.dry:
                    self.dev.tap(m.spool.x, m.spool.y)
                    self.strat.remember(m)
                    self.taps += 1
                    self.play_taps += 1
                time.sleep(cfg.tap_gap)
            time.sleep(cfg.after_burst if dec.moves else cfg.idle_delay)
            return

        # --- fuori dal livello
        self.level_streak = 0
        now = time.monotonic()
        line = f"[{self.frame}] {kind or 'schermata sconosciuta'}"
        self._keep_frame(img, line)
        if not cfg.auto_buttons:
            print(line)
            time.sleep(cfg.idle_delay)
            return

        if kind and kind.startswith("vite finite"):
            self.unknown_since = None
            self.lives_since = self.lives_since or now
            wait = now - self.lives_since
            print(f"{line}: aspetto le vite ({wait:.0f}/{cfg.lives_retry:.0f} s)")
            if wait > cfg.lives_retry:
                x = screens.find_close(arr)
                if x and not self.dry:
                    print("   chiudo il pannello e riprovo")
                    self.dev.tap(*x)
                self.lives_since = None
                self.last_screen_tap = now
            time.sleep(2.0)
            return

        if kind and pt:
            self.unknown_since = None
            if kind == self.last_screen and now - self.last_screen_tap > cfg.screen_tap_every:
                print(f"{line}: tocco {pt}")
                if not self.dry:
                    self.dev.tap(*pt)
                self.last_screen_tap = now
                kind = None
            else:
                print(line)
            self.last_screen = kind
            time.sleep(cfg.idle_delay)
            return

        # schermata sconosciuta: dopo un po' tasto Indietro (chiude pubblicita' e pannelli)
        self.last_screen = None
        self.unknown_since = self.unknown_since or now
        still = now - self.unknown_since
        print(f"{line} da {still:.0f} s")
        if cfg.back_after > 0 and still > cfg.back_after and now - self.last_back > cfg.back_after:
            print("   tasto Indietro")
            if not self.dry:
                self.dev.back()
            self.last_back = now
        time.sleep(cfg.idle_delay)

    def run(self):
        print("Avviato. Ctrl+C per fermare.")
        try:
            while self.args.max_taps is None or self.taps < self.args.max_taps:
                try:
                    self.step()
                except Exception as e:  # telefono scollegato un attimo, ecc.: si riprova
                    print(f"   errore: {e}")
                    time.sleep(2)
        except KeyboardInterrupt:
            pass
        self.dev.close()
        print(f"Fermato. Tocchi: {self.taps}, livelli vinti: {self.wins}, persi: {self.losses}")
