"""Groq's OpenAI-compatible API (free tier): fast text fallback when Gemini is rate limited. No images."""
from __future__ import annotations

import os
import time

import httpx

from .. import config
from .base import Completion, Attachment, Provider, ProviderError

URL = "https://api.groq.com/openai/v1/chat/completions"


class Groq(Provider):
    name = "groq"

    def __init__(self) -> None:
        self.model = os.environ.get("CJ_GROQ_MODEL", "llama-3.3-70b-versatile")

    def available(self) -> bool:
        return bool(config.key("groq"))

    async def complete(self, system, messages, image: Attachment | None = None, json_mode=False) -> Completion:
        body = {"model": self.model, "temperature": 0.6, "max_tokens": 800,
                "messages": [{"role": "system", "content": system}] +
                            [{"role": m["role"], "content": m["text"]} for m in messages]}
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        started = time.monotonic()
        async with httpx.AsyncClient(timeout=30) as client:
            r = await client.post(URL, json=body, headers={"Authorization": f"Bearer {config.key('groq')}"})
        if r.status_code != 200:
            raise ProviderError(f"groq {r.status_code}: {r.text[:200]}")
        data = r.json()
        usage = data.get("usage", {})
        return Completion(data["choices"][0]["message"]["content"], self.name, self.model,
                          int((time.monotonic() - started) * 1000),
                          usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0))
