"""CJ brain server. Run from brain/:  uvicorn app.main:app --host 0.0.0.0 --port 8765"""
from __future__ import annotations

import asyncio
import base64
import secrets
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field, model_validator

from . import config, discovery, memory, tts
from .events import bus
from .models.base import Attachment
from .models.router import router
from .modes import assistant


@asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(discovery.announce())  # so the phone finds the brain after an address change
    try:
        yield
    finally:
        task.cancel()


app = FastAPI(title="CJ brain", lifespan=lifespan)
DASHBOARD = Path(__file__).parent / "dashboard" / "index.html"


def _valid(token: str) -> bool:
    return secrets.compare_digest(token.encode(), config.token().encode())


def require_auth(authorization: str = Header(default="")) -> None:
    if not _valid(authorization.removeprefix("Bearer ").strip()):
        raise HTTPException(401, "bad or missing pairing token")


class Turn(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    text: str


class AskIn(BaseModel):
    """A typed question (text), or a spoken one (audio_b64, a WAV clip) that the model transcribes."""
    text: str = Field(default="", max_length=4000)
    mode: str = "assistant"
    history: list[Turn] = []
    image_b64: str | None = Field(default=None, max_length=8_000_000)
    mime: str = "image/jpeg"
    audio_b64: str | None = Field(default=None, max_length=4_000_000)  # about 90 s of 16 kHz mono
    audio_mime: str = "audio/wav"
    voice: bool = False  # also return the answer as speech (voice_b64 in the reply)

    @model_validator(mode="after")
    def text_or_audio(self):
        if not self.text.strip() and not self.audio_b64:
            raise ValueError("send text or audio")
        return self


@app.get("/health")
async def health():
    return {"name": "CJ", "ok": True, "providers": router.status()}


@app.post("/api/ask", dependencies=[Depends(require_auth)])
async def ask(body: AskIn):
    started = time.monotonic()
    history = [t.model_dump() for t in body.history]
    try:
        if body.audio_b64 and not body.text.strip():
            out = await assistant.ask_spoken(Attachment(body.audio_b64, body.audio_mime), history)
            bus.emit("heard", mode=body.mode, text=out["heard"] or "(no clear speech)", has_image=False, spoken=True)
        else:
            bus.emit("heard", mode=body.mode, text=body.text, has_image=bool(body.image_b64))
            image = Attachment(body.image_b64, body.mime) if body.image_b64 else None
            out = await assistant.ask(body.text, history, image)
    except Exception as e:
        bus.emit("error", where="ask", error=str(e)[:200])
        raise HTTPException(502, f"no AI provider could answer: {e}"[:300]) from e
    if body.voice and out["spoken"]:
        audio = await tts.speak(out["spoken"])
        if audio:
            out["voice_b64"] = base64.b64encode(audio).decode()
    out["ms"] = int((time.monotonic() - started) * 1000)
    if out["needs_photo"]:
        bus.emit("needs_photo", mode=body.mode, text=out["heard"], ms=out["ms"])
    else:
        bus.emit("answer", mode=body.mode, spoken=out["spoken"], language=out["language"], ms=out["ms"])
    return out


class SpeakIn(BaseModel):
    text: str = Field(min_length=1, max_length=1000)


@app.post("/api/speak", dependencies=[Depends(require_auth)])
async def speak(body: SpeakIn):
    """CJ's voice for things the phone says on its own, like a timer going off."""
    audio = await tts.speak(body.text)
    return {"voice_b64": base64.b64encode(audio).decode() if audio else ""}


@app.get("/api/memory", dependencies=[Depends(require_auth)])
async def memory_list():
    return {"notes": memory.load()}


@app.get("/dashboard")
async def dashboard():
    return FileResponse(DASHBOARD)


@app.websocket("/ws/dashboard")
async def ws_dashboard(ws: WebSocket, token: str = ""):
    if not _valid(token):
        await ws.close(code=4401)
        return
    await ws.accept()
    for event in list(bus.history):
        await ws.send_json(event)
    queue = bus.subscribe()
    try:
        while True:
            await ws.send_json(await queue.get())
    except WebSocketDisconnect:
        pass
    finally:
        bus.unsubscribe(queue)
