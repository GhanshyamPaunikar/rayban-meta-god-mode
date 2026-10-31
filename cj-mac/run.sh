#!/usr/bin/env bash
# Starts the CJ brain on this Mac and prints the pairing QR code. The first run creates .venv.
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv
  .venv/bin/pip install -q -r requirements.txt
fi
.venv/bin/python -m cj.pair
exec .venv/bin/uvicorn cj.main:app --host 0.0.0.0 --port 8765
