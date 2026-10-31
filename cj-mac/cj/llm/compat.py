"""Shared client for the many services that speak OpenAI's Chat Completions format (GPT, Groq, DeepSeek,
OpenRouter, Ollama). Each one is a subclass that only says where it lives and how it's chosen."""
from __future__ import annotations

import os
import time

import httpx

from .. import prefs
from .provider import Attachment, Completion, Provider, ProviderError


class ChatCompletions(Provider):
    url = ""
    images = False         # accepts photos
    needs_key = True       # Ollama runs on your Mac and needs none
    default_model = ""     # "" = you choose one when adding the key (./set-key.sh <name>)
    token_limit = "max_tokens"
    timeout = 30

    @property
    def model(self) -> str:
        return (os.environ.get(f"CJ_{self.name.upper()}_MODEL") or prefs.load().get(f"{self.name}_model")
                or self.default_model)

    def available(self) -> bool:
        return bool(self.model and (prefs.key(self.name) or not self.needs_key))

    def headers(self) -> dict:
        return {"Authorization": f"Bearer {prefs.key(self.name)}"} if self.needs_key else {}

    async def complete(self, system, messages, image: Attachment | None = None, json_mode=False) -> Completion:
        if image and not self.images:
            raise ProviderError(f"{self.name} can't see photos")
        chat = [{"role": "system", "content": system}] + [
            {"role": "assistant" if m["role"] == "assistant" else "user", "content": m["text"]} for m in messages]
        if image and len(chat) > 1:
            chat[-1]["content"] = [{"type": "text", "text": chat[-1]["content"]},
                                   {"type": "image_url", "image_url": {"url": f"data:{image.mime};base64,{image.data_b64}"}}]
        body = {"model": self.model, "messages": chat, self.token_limit: 800}
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        started = time.monotonic()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                r = await client.post(self.url, json=body, headers=self.headers())
        except httpx.HTTPError as e:
            raise ProviderError(f"{self.name}: {type(e).__name__}") from e
        if r.status_code != 200:
            raise ProviderError(f"{self.name} {r.status_code}: {r.text[:200]}")
        data = r.json()
        usage = data.get("usage") or {}
        return Completion(data["choices"][0]["message"].get("content") or "", self.name, self.model,
                          int((time.monotonic() - started) * 1000),
                          usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0))
