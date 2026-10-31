"""Event bus behind mission control: every step CJ takes is emitted here and streamed to the console."""
from __future__ import annotations

import asyncio
import time
from collections import deque


class EventBus:
    def __init__(self, keep: int = 200) -> None:
        self.history: deque[dict] = deque(maxlen=keep)
        self._subs: set[asyncio.Queue] = set()

    def emit(self, kind: str, **data) -> dict:
        event = {"t": round(time.time(), 3), "kind": kind, **data}
        self.history.append(event)
        for q in self._subs:
            q.put_nowait(event)
        return event

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue()
        self._subs.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subs.discard(q)


bus = EventBus()
