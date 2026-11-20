"""Comunicazione con il telefono tramite ADB (debug USB o debug wireless)."""
from __future__ import annotations

import io
import shutil
import subprocess

from PIL import Image


class ADBError(RuntimeError):
    pass


class Device:
    def __init__(self, serial: str | None = None, adb: str = "adb"):
        if shutil.which(adb) is None:
            raise ADBError(
                "Comando 'adb' non trovato. Installa Android Platform Tools "
                "(su Termux: pkg install android-tools)."
            )
        self.base = [adb] + (["-s", serial] if serial else [])

    def _run(self, *args: str, binary: bool = False, timeout: float = 15):
        try:
            p = subprocess.run(self.base + list(args), capture_output=True, timeout=timeout)
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

    def screenshot(self) -> Image.Image:
        data = self._run("exec-out", "screencap", "-p", binary=True)
        if not data.startswith(b"\x89PNG"):
            # alcuni vecchi dispositivi convertono \n in \r\n
            data = data.replace(b"\r\n", b"\n")
        return Image.open(io.BytesIO(data)).convert("RGB")

    def tap(self, x: int, y: int) -> None:
        self._run("shell", "input", "tap", str(int(x)), str(int(y)))
