"""Prints the pairing QR code for the companion app:  python -m cj.pair"""
from __future__ import annotations

import json
import shutil
import socket
import subprocess

import qrcode

from . import prefs

PORT = 8765


def lan_ip() -> str:
    """The Mac's address on your Wi-Fi (or Ethernet), which is what the phone can reach.

    Asking which interface would carry outgoing traffic gives the wrong answer with a VPN on (ProtonVPN, Tailscale…):
    that's the VPN tunnel, and the phone can't reach it. So the real network ports (en0, en1, …) are asked first."""
    return _wifi_ip() or _route_ip()


def _wifi_ip() -> str:
    if not shutil.which("ipconfig"):  # macOS only; elsewhere the route guess is used
        return ""
    for port in range(8):
        try:
            ip = subprocess.run(["ipconfig", "getifaddr", f"en{port}"], capture_output=True, text=True,
                                timeout=2).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return ""
        if ip and not ip.startswith("169.254."):  # 169.254 = no network yet (self-assigned)
            return ip
    return ""


def _route_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; this just picks the outgoing interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main() -> None:
    payload = {"v": 1, "url": f"http://{lan_ip()}:{PORT}", "token": prefs.token()}
    qr = qrcode.QRCode(border=1)
    qr.add_data(json.dumps(payload))
    qr.make()
    qr.print_ascii(invert=True)
    print(f"\nBrain:     {payload['url']}")
    print(f"Console: {payload['url']}/console?token={payload['token']}")
    print("In the CJ app: Setup -> Scan QR code.")


if __name__ == "__main__":
    main()
