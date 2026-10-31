"""Settings and secrets for the brain server, stored in ~/.cj (override with CJ_HOME)."""
from __future__ import annotations

import json
import os
import secrets
from pathlib import Path


def home() -> Path:
    path = Path(os.environ.get("CJ_HOME", Path.home() / ".cj"))
    path.mkdir(parents=True, exist_ok=True)
    return path


def _file() -> Path:
    return home() / "config.json"


def load() -> dict:
    try:
        return json.loads(_file().read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save(cfg: dict) -> None:
    f = _file()
    f.write_text(json.dumps(cfg, indent=2))
    f.chmod(0o600)


def token() -> str:
    """Pairing token the phone sends with every request. Created on first use."""
    cfg = load()
    if not cfg.get("token"):
        cfg["token"] = secrets.token_urlsafe(24)
        save(cfg)
    return cfg["token"]


def key(provider: str) -> str | None:
    """API key from G_<PROVIDER>_KEY, else from the config file. Never from the repo."""
    return os.environ.get(f"CJ_{provider.upper()}_KEY") or load().get("keys", {}).get(provider)


def set_key(provider: str, value: str) -> None:
    cfg = load()
    cfg.setdefault("keys", {})[provider] = value
    save(cfg)
