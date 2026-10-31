"""OpenRouter: one key for hundreds of models, many of them free (names ending in ":free").
Optional:  ./set-key.sh openrouter  asks which model to use. Used as a free fallback after Gemini and Groq."""
from __future__ import annotations

from .compat import ChatCompletions


class OpenRouter(ChatCompletions):
    name = "openrouter"
    url = "https://openrouter.ai/api/v1/chat/completions"

    def headers(self) -> dict:
        # OpenRouter shows these on its usage page; neither carries anything personal.
        return {**super().headers(), "X-Title": "CJ for Ray-Ban Meta"}
