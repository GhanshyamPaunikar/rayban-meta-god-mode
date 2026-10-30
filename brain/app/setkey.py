"""Saves an API key to ~/.cj/config.json (mode 600) from a hidden prompt:
    python -m app.setkey [gemini|groq|azure_speech]
For azure_speech it also asks for the Azure region (e.g. centralindia, eastus)."""
from __future__ import annotations

import getpass
import re
import sys

from . import config


def main() -> None:
    provider = sys.argv[1] if len(sys.argv) > 1 else "gemini"
    value = getpass.getpass(f"Paste your {provider} API key (hidden), then press Enter: ").strip()
    if len(value) < 20 or "YOUR_KEY" in value:
        sys.exit("That doesn't look like a real key (placeholder or too short). Nothing was saved.")
    if provider == "azure_speech":
        region = input("Azure region of that key (e.g. centralindia, eastus): ").strip().lower()
        if not re.fullmatch(r"[a-z0-9]+", region):
            sys.exit("That doesn't look like an Azure region. Nothing was saved.")
        cfg = config.load()
        cfg["azure_region"] = region
        config.save(cfg)
    config.set_key(provider, value)
    print(f"Saved {provider} key to {config.home() / 'config.json'}. The running server picks it up automatically.")


if __name__ == "__main__":
    main()
