"""Comunicazione con il telefono tramite ADB (cavo USB o debug wireless)."""
from __future__ import annotations

import io
import os
import re
import shutil
import subprocess
import sys
import threading

import numpy as np
from PIL import Image


class ADBError(RuntimeError):
    pass


def find_adb(adb: str | None = None) -> str:
    """adb indicato, oppure nel PATH, oppure nella cartella platform-tools accanto al programma."""
    if adb:
        return adb
    found = shutil.which("adb")
    if found:
        return found
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    exe = "adb.exe" if sys.platform == "win32" else "adb"
    for cand in (os.path.join(here, "platform-tools", exe), os.path.join(here, exe)):
        if os.path.exists(cand):
            return cand
    raise ADBError(
        "Comando 'adb' non trovato. Scarica Android Platform Tools e metti la cartella "
        "'platform-tools' dentro la cartella del bot (vedi README)."
    )


class Device:
    def __init__(self, serial: str | None = None, adb: str | None = None):
        self.base = [find_adb(adb)] + (["-s", serial] if serial else [])
        self._shell = None
        self._lock = threading.Lock()
        self.raw_ok = True  # screenshot senza compressione PNG (molto piu' veloce)
        self._no_window = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0

    def _run(self, *args: str, binary: bool = False, timeout: float = 15):
        try:
            p = subprocess.run(self.base + list(args), capture_output=True, timeout=timeout,
                               creationflags=self._no_window)
        except subprocess.TimeoutExpired as e:
            raise ADBError(f"adb non risponde ({' '.join(args)})") from e
        if p.returncode != 0:
            raise ADBError(p.stderr.decode(errors="replace").strip() or f"adb {' '.join(args)} fallito")
        return p.stdout if binary else p.stdout.decode(errors="replace")

    def check(self) -> str:
        out = self._run("get-state").strip()
        if out != "device":
            raise ADBError(f"Telefono non pronto (stato: {out}). Hai autorizzato il debug USB?")
        return self._run("shell", "wm", "size").strip()

    # ------------------------------------------------------------------ schermo
    def screenshot(self) -> Image.Image:
        if self.raw_ok:
            try:
                return self._screenshot_raw()
            except Exception:
                self.raw_ok = False  # telefono che non lo supporta: si passa al PNG
        data = self._run("exec-out", "screencap", "-p", binary=True)
        if not data.startswith(b"\x89PNG"):
            data = data.replace(b"\r\n", b"\n")  # alcuni vecchi telefoni convertono \n in \r\n
        return Image.open(io.BytesIO(data)).convert("RGB")

    def _screenshot_raw(self) -> Image.Image:
        """screencap senza -p: pixel RGBA grezzi (il telefono non perde tempo a comprimere)."""
        data = self._run("exec-out", "screencap", binary=True)
        w, h, fmt = np.frombuffer(data[:12], dtype="<u4")
        size = int(w) * int(h) * 4
        header = len(data) - size
        if fmt != 1 or header not in (12, 16) or w < 100 or h < 100:
            raise ADBError("formato screencap sconosciuto")
        px = np.frombuffer(data, dtype=np.uint8, offset=header, count=size).reshape(int(h), int(w), 4)
        return Image.fromarray(np.ascontiguousarray(px[..., :3]))

    # ------------------------------------------------------------------ tocchi
    def _sh(self):
        """Una sola shell aperta sul telefono: ogni tocco non deve far partire un nuovo adb."""
        if self._shell is None or self._shell.poll() is not None:
            self._shell = subprocess.Popen(self.base + ["shell"], stdin=subprocess.PIPE,
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                           creationflags=self._no_window)
        return self._shell

    def _send(self, cmd: str) -> None:
        with self._lock:
            for _ in range(2):
                try:
                    sh = self._sh()
                    sh.stdin.write((cmd + "\n").encode())
                    sh.stdin.flush()
                    return
                except (BrokenPipeError, OSError):
                    self._shell = None
            self._run("shell", cmd)

    def tap(self, x: int, y: int) -> None:
        # "&" = non aspetta che il tocco finisca: i tocchi di una raffica partono uno dietro l'altro
        self._send(f"input tap {int(x)} {int(y)} &")

    def back(self) -> None:
        self._send("input keyevent 4")

    # ------------------------------------------------------------------ app
    def foreground(self) -> str | None:
        """Pacchetto dell'app in primo piano (es. com.combo.yarnloop)."""
        try:
            out = self._run("shell", "dumpsys", "window", timeout=8)
        except ADBError:
            return None
        for key in ("mCurrentFocus", "mFocusedApp"):
            m = re.search(key + r"=.*?\s([\w.]+)/", out)
            if m:
                return m.group(1)
        return None

    def launch(self, package: str) -> None:
        self._run("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")

    def close(self) -> None:
        if self._shell and self._shell.poll() is None:
            try:
                self._shell.stdin.write(b"exit\n")
                self._shell.stdin.flush()
            except OSError:
                pass
