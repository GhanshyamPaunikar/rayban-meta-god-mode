"""Accessibility settings from the phone (Setup → Accessibility), sent with every question and Live session.

Each switch adds a line to CJ's instructions, so the same models answer in the way that person needs:
reading text word for word for a blind user, naming banknotes, shorter answers, and so on."""
from __future__ import annotations

import contextvars

from pydantic import BaseModel, Field


class Access(BaseModel):
    read_aloud: bool = False
    money: bool = False
    describe: bool = False
    medicine: bool = False
    slow_speech: bool = False
    confirm: bool = False
    detail: str = Field(default="normal", pattern="^(short|normal|detailed)$")


# The settings for the question being answered now, like lookups.LOCATION.
SETTINGS: contextvars.ContextVar[dict | None] = contextvars.ContextVar("access", default=None)

# The user may not be able to see the phone, so what matters goes in the spoken answer, never only in "details".
RULES = {
    "read_aloud": "When asked to read something, the spoken answer is all the text you can see, word for word, in "
                  "reading order. Don't paraphrase, summarise or shorten it, and don't start with \"the sign says\". "
                  "Say if part of it is cut off or blurry.",
    "money": "When you see banknotes or coins, say the value of each one and the total. Say if a note is folded or "
             "hidden and you can't be sure.",
    "describe": "When describing a place, start with what matters for moving safely: steps, kerbs, obstacles, "
                "doors, vehicles and people close by. Give directions as clock positions and rough distances in "
                "steps or metres.",
    "medicine": "When you see a medicine, say its name, strength, how to take it and the expiry date, and say "
                "clearly if it has expired. Never guess a dose you can't read; say you can't read it.",
    "confirm": "Before calling, messaging, paying or ordering anything, always say exactly what you are about to "
               "do and wait for a clear yes.",
}

DETAIL = {
    "short": "Keep every spoken answer to one short sentence.",
    "detailed": "Give fuller spoken answers than usual: three or four sentences, step by step when that helps.",
}


def prompt(settings: dict | None = None) -> str:
    """Extra instructions for the switches that are on; empty when none are."""
    s = SETTINGS.get() if settings is None else settings
    if not s:
        return ""
    lines = [rule for key, rule in RULES.items() if s.get(key)]
    if s.get("detail") in DETAIL:
        lines.append(DETAIL[s["detail"]])
    if not lines:
        return ""
    # Last in the instructions, and explicit, or the usual "one or two short sentences" rule wins.
    return ("\nThe user's accessibility settings. They override every rule above about how long the spoken answer "
            "is or what goes in \"details\": anything they ask for must be in the spoken answer itself."
            + "".join(f"\n- {line}" for line in lines))


def slow_speech(settings: dict | None = None) -> bool:
    s = SETTINGS.get() if settings is None else settings
    return bool(s and s.get("slow_speech"))
