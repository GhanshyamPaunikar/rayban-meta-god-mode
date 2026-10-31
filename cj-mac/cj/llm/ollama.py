"""Ollama: a model running on your own Mac, free and offline. Install Ollama, pull a model (ollama pull llama3.2),
then  ./set-key.sh ollama  to tell CJ its name. The last fallback before the offline stand-in."""
from __future__ import annotations

import os

from .compat import ChatCompletions


class Ollama(ChatCompletions):
    name = "ollama"
    needs_key = False
    timeout = 60  # a laptop is slower than a data centre

    @property
    def url(self) -> str:
        return os.environ.get("CJ_OLLAMA_URL", "http://localhost:11434") + "/v1/chat/completions"
