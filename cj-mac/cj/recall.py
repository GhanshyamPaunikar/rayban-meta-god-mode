"""CJ's memory: what you ask it to remember, what it picks up from your conversations, and what it saw.

Stored in ~/.cj/recall.db (SQLite) on your Mac. Three kinds of note:
- "note":    "remember I parked on level 2"
- "learned": facts CJ noticed on its own a minute after a conversation went quiet ("my sister lives in Pune")
- "seen":    a line for every glasses photo, so "where did I leave my keys?" can find the last sighting

A note can carry a topic ("parking spot"): saving a new note on the same topic replaces the old one, so the newest
answer is always the one you get. Plans can carry an end date ("until"), after which they disappear.

Search matches words in any script (Hindi in Devanagari too). With a Gemini key it also matches by meaning, so
"meri gaadi kahan hai?" finds "parked on level 2, pillar B12".
"""
from __future__ import annotations

import asyncio
import datetime
import json
import logging
import math
import re
import sqlite3
import struct
import threading
from collections import Counter

import httpx

from . import prefs

log = logging.getLogger("cj.recall")

MAX_NOTES = 2000
MAX_SEEN = 1500  # visual memory adds ~100 notes a day: it gets its own budget, so it never pushes out what you saved
QUIET_SECONDS = 60  # learn from a conversation this long after it goes quiet
EMBED_MODEL = "gemini-embedding-001"
EMBED_URL = "https://generativelanguage.googleapis.com/v1beta/models/{m}:batchEmbedContents"
CLOSE_ENOUGH = 0.70  # cosine similarity a note needs to count as "about the same thing" (unrelated notes score ~0.65)

SCHEMA = """
CREATE TABLE IF NOT EXISTS notes (
    id    INTEGER PRIMARY KEY,
    t     TEXT NOT NULL,              -- local time, minutes
    kind  TEXT NOT NULL,              -- note | learned | seen
    text  TEXT NOT NULL,
    topic TEXT,                       -- a newer note on the same topic replaces this one
    until TEXT,                       -- YYYY-MM-DD: gone after this day
    vec   BLOB                        -- embedding, filled in lazily
);
CREATE INDEX IF NOT EXISTS notes_topic ON notes(topic);
"""

_lock = threading.RLock()
_db: tuple[str, sqlite3.Connection] | None = None


def _conn() -> sqlite3.Connection:
    """One connection per data folder (tests switch CJ_HOME). Imports the old memory.json the first time."""
    global _db
    path = prefs.home() / "recall.db"
    if _db and _db[0] == str(path):
        return _db[1]
    conn = sqlite3.connect(str(path), check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    path.chmod(0o600)
    _db = (str(path), conn)
    _import_json(conn)
    return conn


def _import_json(conn: sqlite3.Connection) -> None:
    old = prefs.home() / "memory.json"
    if not old.exists():
        return
    try:
        notes = json.loads(old.read_text())
        with conn:
            conn.executemany("INSERT INTO notes (t, kind, text) VALUES (?, ?, ?)",
                             [(n["t"], n.get("kind", "note"), n["text"]) for n in notes if n.get("text")])
        old.rename(old.with_suffix(".json.imported"))
    except (OSError, ValueError, KeyError) as e:
        log.warning("couldn't import memory.json: %s", e)


def _now() -> str:
    return datetime.datetime.now().isoformat(timespec="minutes")


def _row(r: sqlite3.Row) -> dict:
    note = {"id": r["id"], "t": r["t"], "kind": r["kind"], "text": r["text"]}
    if r["topic"]:
        note["topic"] = r["topic"]
    if r["until"]:
        note["until"] = r["until"]
    return note


def _expire(conn: sqlite3.Connection) -> None:
    conn.execute("DELETE FROM notes WHERE until IS NOT NULL AND until < ?", (datetime.date.today().isoformat(),))


def _trim(conn: sqlite3.Connection) -> None:
    for kinds, keep in (("('seen')", MAX_SEEN), ("('note', 'learned')", MAX_NOTES)):
        conn.execute(f"DELETE FROM notes WHERE kind IN {kinds} AND id NOT IN "
                     f"(SELECT id FROM notes WHERE kind IN {kinds} ORDER BY id DESC LIMIT ?)", (keep,))


def load() -> list[dict]:
    """Every live note, oldest first."""
    with _lock:
        conn = _conn()
        with conn:
            _expire(conn)
        return [_row(r) for r in conn.execute("SELECT * FROM notes ORDER BY id")]


def clear() -> None:
    with _lock:
        conn = _conn()
        with conn:
            conn.execute("DELETE FROM notes")


def add(text: str, kind: str = "note", topic: str = "", until: str = "") -> dict:
    text, topic = text.strip()[:500], _topic(topic)
    until = until if re.fullmatch(r"\d{4}-\d{2}-\d{2}", until or "") else ""
    note = {"t": _now(), "kind": kind, "text": text}
    if not text:
        return note
    with _lock:
        conn = _conn()
        with conn:
            if topic:  # the newest answer wins: "parked at B2" replaces "parked at level 3"
                conn.execute("DELETE FROM notes WHERE topic = ?", (topic,))
            cur = conn.execute("INSERT INTO notes (t, kind, text, topic, until) VALUES (?, ?, ?, ?, ?)",
                               (note["t"], kind, text, topic or None, until or None))
            _trim(conn)
    note["id"] = cur.lastrowid
    if topic:
        note["topic"] = topic
    if until:
        note["until"] = until
    return note


def edit(note_id: int, text: str) -> bool:
    with _lock:
        conn = _conn()
        with conn:
            return conn.execute("UPDATE notes SET text = ?, vec = NULL WHERE id = ?",
                                (text.strip()[:500], note_id)).rowcount > 0


def delete(note_id: int) -> bool:
    with _lock:
        conn = _conn()
        with conn:
            return conn.execute("DELETE FROM notes WHERE id = ?", (note_id,)).rowcount > 0


def forget(query: str) -> int:
    """Removes the notes that clearly match, so "forget my locker code" won't also wipe the parking spot."""
    hits = search(query, k=3, kinds=("note", "learned"))
    best = hits[0]["_score"] if hits else 0
    ids = [h["id"] for h in hits if h["_score"] >= best * 0.6]
    with _lock:
        conn = _conn()
        with conn:
            conn.executemany("DELETE FROM notes WHERE id = ?", [(i,) for i in ids])
    return len(ids)


def _topic(text: str) -> str:
    return " ".join(_tokens(text or ""))[:60]


def _tokens(text: str) -> list[str]:
    """Words in any script. Devanagari vowel signs aren't "word" characters to Python, so they're added explicitly."""
    return [t for t in re.findall(r"[\wऀ-ॿ]+", text.lower()) if len(t) > 1]


def search(query: str, k: int = 5, notes: list[dict] | None = None, kinds: tuple = ()) -> list[dict]:
    """Word overlap, weighted towards rarer words and newer notes. Each hit carries its "_score"."""
    notes = load() if notes is None else notes
    if kinds:
        notes = [n for n in notes if n["kind"] in kinds]
    q = Counter(_tokens(query))
    if not notes or not q:
        return []
    docs = [Counter(_tokens(n["text"] + " " + n.get("topic", ""))) for n in notes]
    df = Counter(t for d in docs for t in d)
    scored = []
    for age, (note, d) in enumerate(zip(reversed(notes), reversed(docs))):
        score = sum(math.log(1 + len(notes) / df[t]) for t in q if t in d)
        if score > 0:
            scored.append((score / (1 + age * 0.01), note))
    scored.sort(key=lambda s: -s[0])
    return [{**n, "_score": s} for s, n in scored[:k]]


async def find(query: str, k: int = 5) -> list[dict]:
    """Search by words, and by meaning when a Gemini key is set. Falls back to words alone if embedding fails."""
    by_words = search(query, k=k)
    if not prefs.key("gemini"):
        return by_words
    try:
        by_meaning = await _by_meaning(query, k)
    except Exception as e:  # offline or rate limited: words alone are still a good answer
        log.info("search by meaning unavailable: %s", e)
        return by_words
    merged, ids = [], set()
    for n in by_meaning + by_words:  # meaning first: it catches other languages and other wordings
        if n["id"] not in ids:
            ids.add(n["id"])
            merged.append(n)
    return merged[:k]


async def _by_meaning(query: str, k: int) -> list[dict]:
    with _lock:
        rows = list(_conn().execute("SELECT * FROM notes ORDER BY id DESC LIMIT 600"))
    missing = [r for r in rows if r["vec"] is None][:99]
    vectors = await _embed([query] + [r["text"] for r in missing])
    q, fresh = vectors[0], {r["id"]: v for r, v in zip(missing, vectors[1:])}
    if fresh:
        with _lock:
            conn = _conn()
            with conn:
                conn.executemany("UPDATE notes SET vec = ? WHERE id = ?", [(_pack(v), i) for i, v in fresh.items()])
    scored = []
    for r in rows:
        v = fresh.get(r["id"]) or (_unpack(r["vec"]) if r["vec"] else None)
        if v:
            scored.append((_cosine(q, v), r))
    scored.sort(key=lambda s: -s[0])
    if not scored or scored[0][0] < CLOSE_ENOUGH:
        return []
    top = scored[0][0]
    # Only notes nearly as close as the best one, so a clear answer isn't diluted by loosely related notes.
    return [{**_row(r), "_score": s} for s, r in scored[:k] if s >= max(CLOSE_ENOUGH, top - 0.08)]


async def _embed(texts: list[str]) -> list[list[float]]:
    body = {"requests": [{"model": f"models/{EMBED_MODEL}", "content": {"parts": [{"text": t[:2000]}]},
                          "outputDimensionality": 256} for t in texts]}
    async with httpx.AsyncClient(timeout=10) as client:
        r = await client.post(EMBED_URL.format(m=EMBED_MODEL), json=body,
                              headers={"x-goog-api-key": prefs.key("gemini") or ""})
        r.raise_for_status()
    return [e["values"] for e in r.json()["embeddings"]]


def _pack(v: list[float]) -> bytes:
    return struct.pack(f"{len(v)}f", *v)


def _unpack(b: bytes) -> list[float]:
    return list(struct.unpack(f"{len(b) // 4}f", b))


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na, nb = math.sqrt(sum(x * x for x in a)), math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def seen_since(hours: int) -> list[dict]:
    """What the glasses noticed in the last few hours, oldest first (at most 40)."""
    since = (datetime.datetime.now() - datetime.timedelta(hours=hours)).isoformat(timespec="minutes")
    return [m for m in load() if m["kind"] == "seen" and m["t"] >= since][-40:]


def recent(n: int = 12) -> list[dict]:
    return [m for m in load() if m["kind"] in ("note", "learned")][-n:]


def describe(notes: list[dict]) -> str:
    def line(m: dict) -> str:
        tag = {"seen": "(seen) ", "learned": "(picked up) "}.get(m["kind"], "")
        end = f" (until {m['until']})" if m.get("until") else ""
        return f"- [{m['t'].replace('T', ' ')}] {tag}{m['text']}{end}"
    return "\n".join(line(m) for m in notes)


def prompt() -> str:
    """Recent notes go into every prompt, so CJ knows your city, people and plans without being asked."""
    notes = recent()
    return "\nWhat you know about the user (use it when relevant; newest is true):\n" + describe(notes) if notes else ""


# ---- learning from conversation ----------------------------------------------------------------------------

LEARN = """Below is part of a conversation between a user and their voice assistant. List the facts about the
USER worth remembering for weeks: their people, places, plans, routines, preferences, belongings and where things
are. Skip small talk, questions about the world, and anything the assistant said on its own.
For each fact give:
- "fact": one short sentence in English, in the third person ("The user's sister lives in Pune").
- "topic": a 1-3 word label if a newer fact on the same subject should replace this one ("parking spot",
  "sister's city"), else "".
- "until": the last day it matters as YYYY-MM-DD for plans and appointments, else "". Today is {today}.
Answer with JSON only: {{"facts": [...]}}. If there is nothing worth keeping, answer {{"facts": []}}."""


class Learner:
    """Collects what was said and, once the conversation has been quiet for a minute, saves new facts."""

    def __init__(self) -> None:
        self.lines: list[str] = []
        self.timer: asyncio.TimerHandle | None = None

    def heard(self, role: str, text: str) -> None:
        text = (text or "").strip()
        if not text:
            return
        self.lines.append(f"{role}: {text[:400]}")
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return  # not inside the server (a script or a sync test): nothing to schedule
        if self.timer:
            self.timer.cancel()
        self.timer = loop.call_later(QUIET_SECONDS, lambda: asyncio.ensure_future(self.flush()))

    async def flush(self) -> list[dict]:
        lines, self.lines = self.lines[-60:], []
        if not any(line.startswith("user:") for line in lines):
            return []
        return await learn("\n".join(lines))


async def learn(transcript: str) -> list[dict]:
    from .modes.common import ask_json  # late import: modes imports this module

    try:
        data, _ = await ask_json("learn", LEARN.format(today=datetime.date.today().isoformat()),
                                 [{"role": "user", "text": transcript[-6000:]}])
    except Exception as e:
        log.info("learning skipped: %s", e)
        return []
    facts = data.get("facts") if isinstance(data.get("facts"), list) else []
    known = {n["text"].lower() for n in recent(200)}
    saved = []
    for f in facts:
        fact = str(f.get("fact", "")).strip() if isinstance(f, dict) else ""
        if fact and fact.lower() not in known:
            saved.append(add(fact, kind="learned", topic=str(f.get("topic") or ""), until=str(f.get("until") or "")))
    return saved


learner = Learner()
