"""Prints the pairing QR code for the companion app:  python -m app.pair"""
from __future__ import annotations

import json
import socket

import qrcode

from . import config

PORT = 8765


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; this just picks the outgoing interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main() -> None:
    payload = {"v": 1, "url": f"http://{lan_ip()}:{PORT}", "token": config.token()}
    qr = qrcode.QRCode(border=1)
    qr.add_data(json.dumps(payload))
    qr.make()
    qr.print_ascii(invert=True)
    print(f"\nBrain:     {payload['url']}")
    print(f"Dashboard: {payload['url']}/dashboard?token={payload['token']}")
    print("In the CJ app: Setup -> Scan QR code.")


if __name__ == "__main__":
    main()
