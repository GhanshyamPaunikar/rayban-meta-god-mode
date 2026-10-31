"""Monthly spending limits for paid models (Claude, GPT, DeepSeek), kept in ~/.cj/spend.json.

You set a limit and the provider's prices when adding its key (./set-key.sh claude). CJ adds up what each call
cost from the tokens the provider reports; once a provider reaches its limit for the month, CJ stops using it
and falls back to the free models until the 1st. Free providers are never counted.
"""
from __future__ import annotations

import json
import time

from .. import prefs
from ..events import bus

PAID = {"claude", "openai", "deepseek"}
WARN_AT = 0.8


def _file():
    return prefs.home() / "spend.json"


def _month() -> str:
    return time.strftime("%Y-%m")


def _load() -> dict:
    try:
        data = json.loads(_file().read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        data = {}
    return data if data.get("month") == _month() else {"month": _month(), "usd": {}}


def limit(provider: str) -> float | None:
    value = prefs.load().get("limits", {}).get(provider)
    return float(value) if value not in (None, "") else None


def cost(provider: str, tokens_in: int, tokens_out: int) -> float:
    """US dollars for one call, from the prices you entered (per million tokens). Unknown prices count as 0."""
    price = prefs.load().get("prices", {}).get(provider) or {}
    return (tokens_in * float(price.get("in", 0)) + tokens_out * float(price.get("out", 0))) / 1_000_000


def spent(provider: str) -> float:
    return float(_load()["usd"].get(provider, 0.0))


def record(provider: str, tokens_in: int, tokens_out: int) -> None:
    if provider not in PAID:
        return
    data = _load()
    before = float(data["usd"].get(provider, 0.0))
    after = before + cost(provider, tokens_in, tokens_out)
    data["usd"][provider] = round(after, 6)
    _file().write_text(json.dumps(data))
    _file().chmod(0o600)
    cap = limit(provider)
    if cap and before < cap * WARN_AT <= after:
        bus.emit("spend_warning", provider=provider, spent=round(after, 2), limit=cap)


def over(provider: str) -> bool:
    cap = limit(provider)
    return provider in PAID and cap is not None and spent(provider) >= cap


def status() -> dict:
    return {p: {"spent": round(spent(p), 2), "limit": limit(p)} for p in sorted(PAID) if limit(p) is not None or spent(p)}
