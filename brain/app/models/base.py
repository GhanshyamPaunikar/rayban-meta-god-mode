from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Attachment:
    """A photo or an audio clip sent with the question: base64 data and its MIME type."""
    data_b64: str
    mime: str = "image/jpeg"


@dataclass
class Completion:
    text: str
    provider: str
    model: str
    latency_ms: int
    input_tokens: int = 0
    output_tokens: int = 0


class ProviderError(Exception):
    pass


class Provider:
    """One AI service. Messages are [{"role": "user" | "assistant", "text": str}]."""

    name = "base"

    def available(self) -> bool:
        raise NotImplementedError

    async def complete(self, system: str, messages: list[dict], image: Attachment | None = None,
                       json_mode: bool = False) -> Completion:
        raise NotImplementedError
