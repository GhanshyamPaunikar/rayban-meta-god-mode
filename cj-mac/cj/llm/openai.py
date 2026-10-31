"""OpenAI GPT models. Optional: add a key and pick a model with  ./set-key.sh openai"""
from __future__ import annotations

from .compat import ChatCompletions


class OpenAI(ChatCompletions):
    name = "openai"
    url = "https://api.openai.com/v1/chat/completions"
    images = True
    token_limit = "max_completion_tokens"
    # No built-in default model: OpenAI renames models often, so you choose one when adding the key.
