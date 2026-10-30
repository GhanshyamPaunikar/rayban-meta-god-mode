"""Picks a provider for each request and falls back down the chain when one fails or is rate limited.

Chain: gemini -> groq (text only) -> mock. More providers (OpenAI, DeepSeek, local models) can plug in here.
"""
from __future__ import annotations

import os

from ..events import bus
from .base import Completion, Attachment, Provider, ProviderError
from .gemini import Gemini
from .groq import Groq
from .mock import Mock


class Router:
    def __init__(self) -> None:
        self.providers: list[Provider] = [Gemini(), Groq()]
        self.mock = Mock()

    def chain(self, needs_image: bool) -> list[Provider]:
        if os.environ.get("CJ_FORCE_MOCK") == "1":
            return [self.mock]
        usable = [p for p in self.providers if p.available() and not (needs_image and p.name == "groq")]
        return usable or [self.mock]

    def status(self) -> dict:
        return {p.name: p.available() for p in self.providers} | {"mock": True}

    async def complete(self, task: str, system: str, messages: list[dict], image: Attachment | None = None,
                       json_mode: bool = True) -> Completion:
        last_error: Exception | None = None
        for provider in self.chain(image is not None):
            try:
                result = await provider.complete(system, messages, image, json_mode)
                bus.emit("model_call", task=task, provider=result.provider, model=result.model,
                         latency_ms=result.latency_ms, tokens_in=result.input_tokens,
                         tokens_out=result.output_tokens)
                return result
            except Exception as e:  # network error, rate limit, bad response: try the next one
                last_error = e
                bus.emit("model_failed", task=task, provider=provider.name, error=f"{type(e).__name__}: {e}"[:200])
        raise ProviderError(f"all providers failed: {type(last_error).__name__}: {last_error}")


router = Router()
