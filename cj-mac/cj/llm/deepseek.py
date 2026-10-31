"""DeepSeek: a low-cost paid model. Optional:  ./set-key.sh deepseek  (it can also become CJ's main brain). No images."""
from __future__ import annotations

from .compat import ChatCompletions


class DeepSeek(ChatCompletions):
    name = "deepseek"
    url = "https://api.deepseek.com/chat/completions"
    default_model = "deepseek-chat"
