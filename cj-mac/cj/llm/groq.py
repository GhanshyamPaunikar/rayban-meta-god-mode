"""Groq (free tier): fast text fallback when Gemini is rate limited. No images."""
from __future__ import annotations

from .compat import ChatCompletions


class Groq(ChatCompletions):
    name = "groq"
    url = "https://api.groq.com/openai/v1/chat/completions"
    default_model = "llama-3.3-70b-versatile"
