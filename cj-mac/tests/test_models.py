"""More models (DeepSeek, OpenRouter, Ollama) and monthly spending limits for the paid ones."""
import asyncio
import json
import os
import tempfile

os.environ.setdefault("CJ_HOME", tempfile.mkdtemp())

import httpx
import pytest

from cj import prefs
from cj.llm import compat, spend
from cj.llm.chain import Router
from cj.llm.deepseek import DeepSeek
from cj.llm.ollama import Ollama
from cj.llm.openrouter import OpenRouter
from cj.llm.provider import Attachment, ProviderError


@pytest.fixture(autouse=True)
def clean(monkeypatch):
    monkeypatch.delenv("CJ_FORCE_MOCK", raising=False)
    monkeypatch.delenv("CJ_PROVIDER", raising=False)
    cfg = prefs.load()
    for k in ("limits", "prices", "provider", "keys", "ollama_model", "openrouter_model"):
        cfg.pop(k, None)
    prefs.save(cfg)
    spend._file().unlink(missing_ok=True)


def fake_api(monkeypatch, handler):
    real = httpx.AsyncClient
    monkeypatch.setattr(compat.httpx, "AsyncClient", lambda **kw: real(transport=httpx.MockTransport(handler), **kw))


def reply(text="hi", tokens=(1000, 200)):
    return httpx.Response(200, json={"choices": [{"message": {"content": text}}],
                                     "usage": {"prompt_tokens": tokens[0], "completion_tokens": tokens[1]}})


def test_deepseek_speaks_the_openai_format(monkeypatch):
    seen = {}

    def handler(request):
        seen.update(url=str(request.url), auth=request.headers["authorization"], body=json.loads(request.content))
        return reply('{"spoken": "ok"}')
    fake_api(monkeypatch, handler)
    prefs.set_key("deepseek", "sk-test-deepseek-key-123456")
    out = asyncio.run(DeepSeek().complete("system", [{"role": "user", "text": "hello"}], json_mode=True))
    assert out.text == '{"spoken": "ok"}' and out.provider == "deepseek" and out.model == "deepseek-chat"
    assert seen["url"] == "https://api.deepseek.com/chat/completions"
    assert seen["auth"] == "Bearer sk-test-deepseek-key-123456"
    assert seen["body"]["messages"][0] == {"role": "system", "content": "system"}
    assert seen["body"]["response_format"] == {"type": "json_object"} and seen["body"]["max_tokens"] == 800


def test_text_only_models_refuse_photos():
    with pytest.raises(ProviderError):
        asyncio.run(DeepSeek().complete("s", [{"role": "user", "text": "what is this"}], Attachment("x")))


def test_openrouter_needs_a_model_and_ollama_needs_no_key():
    prefs.set_key("openrouter", "sk-or-test-key-1234567890")
    assert not OpenRouter().available()  # a key alone isn't enough: you choose the model
    cfg = prefs.load()
    cfg.update({"openrouter_model": "meta-llama/llama-3.3-70b-instruct:free", "ollama_model": "llama3.2"})
    prefs.save(cfg)
    assert OpenRouter().available() and Ollama().available()
    assert Ollama().headers() == {} and Ollama().url == "http://localhost:11434/v1/chat/completions"


def test_free_fallbacks_come_after_gemini_and_skip_photos_they_cant_see(monkeypatch):
    r = Router()
    for p in r.providers.values():
        monkeypatch.setattr(p.__class__, "available", lambda self: True)
    assert [p.name for p in r.chain()] == ["gemini", "groq", "openrouter", "ollama"]
    assert [p.name for p in r.chain(Attachment("x"))] == ["gemini"]


def test_a_paid_brain_is_dropped_once_it_reaches_its_monthly_limit(monkeypatch):
    r = Router()
    for p in r.providers.values():
        monkeypatch.setattr(p.__class__, "available", lambda self: True)
    cfg = prefs.load()
    cfg.update({"provider": "deepseek", "limits": {"deepseek": 1.0}, "prices": {"deepseek": {"in": 100, "out": 500}}})
    prefs.save(cfg)
    assert r.preferred() == "deepseek"
    spend.record("deepseek", 5000, 500)   # 0.5 + 0.25 = 0.75 dollars
    assert round(spend.spent("deepseek"), 2) == 0.75 and r.preferred() == "deepseek"
    spend.record("deepseek", 2500, 0)     # +0.25 = 1.00: at the limit
    assert spend.over("deepseek") and r.preferred() == "gemini"
    assert spend.status() == {"deepseek": {"spent": 1.0, "limit": 1.0}}


def test_spending_resets_each_month_and_free_models_are_never_counted():
    cfg = prefs.load()
    cfg.update({"limits": {"claude": 5}, "prices": {"claude": {"in": 3, "out": 15}, "groq": {"in": 1, "out": 1}}})
    prefs.save(cfg)
    spend.record("groq", 10**6, 10**6)
    assert spend.spent("groq") == 0
    spend._file().write_text(json.dumps({"month": "1999-01", "usd": {"claude": 99}}))
    assert spend.spent("claude") == 0 and not spend.over("claude")


def test_calls_through_the_chain_are_counted(monkeypatch):
    fake_api(monkeypatch, lambda request: reply(tokens=(1_000_000, 0)))
    prefs.set_key("deepseek", "sk-test-deepseek-key-123456")
    cfg = prefs.load()
    cfg.update({"provider": "deepseek", "prices": {"deepseek": {"in": 0.5, "out": 2}}})
    prefs.save(cfg)
    asyncio.run(Router().complete("test", "system", [{"role": "user", "text": "hi"}], json_mode=False))
    assert spend.spent("deepseek") == 0.5
