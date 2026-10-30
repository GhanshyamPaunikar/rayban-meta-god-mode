"""Gemini over its REST API (free tier with a key from Google AI Studio)."""
from __future__ import annotations

import logging
import os
import time

import httpx

from .. import config
from .base import Completion, Attachment, Provider, ProviderError

URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
BUSY = {429, 500, 503}  # overloaded or rate limited: try the next model
SLOW_SECONDS = 8  # a model slower than this is benched; voice answers can't wait longer
BENCH_SECONDS = 300
log = logging.getLogger("g.gemini")

# Google's free tier shifts load between models: on 30 Oct the "latest" alias took 15-25 s while
# gemini-3.1-flash-lite answered in ~1 s. So CJ keeps a list and benches whichever is slow or busy.
DEFAULT_MODELS = "gemini-3.1-flash-lite,gemini-flash-lite-latest,gemini-flash-latest"


class Gemini(Provider):
    name = "gemini"

    def __init__(self) -> None:
        self.models = [m.strip() for m in os.environ.get("CJ_GEMINI_MODELS", DEFAULT_MODELS).split(",") if m.strip()]
        self._benched: dict[str, float] = {}

    def available(self) -> bool:
        return bool(config.key("gemini"))

    def _order(self) -> list[str]:
        """Healthy models first (in preference order), benched ones last as a final resort."""
        now = time.monotonic()
        healthy = [m for m in self.models if self._benched.get(m, 0) <= now]
        return healthy + [m for m in self.models if m not in healthy]

    def _bench(self, model: str, why: str) -> None:
        self._benched[model] = time.monotonic() + BENCH_SECONDS
        log.warning("benching %s for %d s: %s", model, BENCH_SECONDS, why)

    async def complete(self, system, messages, image: Attachment | None = None, json_mode=False) -> Completion:
        contents = [{"role": "model" if m["role"] == "assistant" else "user",
                     "parts": [{"text": m["text"]}]} for m in messages]
        if image and contents:
            contents[-1]["parts"].append({"inlineData": {"mimeType": image.mime, "data": image.data_b64}})
        body = {"systemInstruction": {"parts": [{"text": system}]}, "contents": contents,
                "generationConfig": {"temperature": 0.6, "maxOutputTokens": 800}}
        if json_mode:
            body["generationConfig"]["responseMimeType"] = "application/json"
        started = time.monotonic()
        order = self._order()
        last_error = "no model tried"
        async with httpx.AsyncClient() as client:
            for i, model in enumerate(order):
                final = i == len(order) - 1
                try:
                    r = await client.post(URL.format(model=model), json=body, timeout=30 if final else SLOW_SECONDS,
                                          headers={"x-goog-api-key": config.key("gemini")})
                except httpx.TimeoutException:
                    self._bench(model, "slow")
                    last_error = f"{model} timed out"
                    continue
                except httpx.HTTPError as e:
                    last_error = f"{model}: {e}"
                    continue
                if r.status_code in BUSY or r.status_code == 404:
                    self._bench(model, f"HTTP {r.status_code}")
                    last_error = f"{model} {r.status_code}: {r.text[:120]}"
                    continue
                if r.status_code != 200:
                    raise ProviderError(f"gemini {model} {r.status_code}: {r.text[:200]}")
                data = r.json()
                try:
                    text = "".join(p.get("text", "") for p in data["candidates"][0]["content"]["parts"])
                except (KeyError, IndexError) as e:
                    raise ProviderError(f"gemini returned no text: {str(data)[:200]}") from e
                usage = data.get("usageMetadata", {})
                return Completion(text, self.name, model, int((time.monotonic() - started) * 1000),
                                  usage.get("promptTokenCount", 0), usage.get("candidatesTokenCount", 0))
        raise ProviderError(f"every Gemini model was slow or busy (last: {last_error})")
