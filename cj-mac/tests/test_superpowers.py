"""Live tools, memory, timers and Mac notes, driven through /api/ask with the offline mock model."""
import os
import tempfile

os.environ.setdefault("CJ_HOME", tempfile.mkdtemp())
os.environ["CJ_FORCE_MOCK"] = "1"

import pytest
from fastapi.testclient import TestClient

from cj import prefs, mac, recall, lookups
from cj.main import app

client = TestClient(app)


def ask(text):
    r = client.post("/api/ask", json={"text": text}, headers={"Authorization": f"Bearer {prefs.token()}"})
    assert r.status_code == 200, r.text
    return r.json()


@pytest.fixture(autouse=True)
def clean_memory():
    recall.clear()


def test_weather_lookup_feeds_the_answer(monkeypatch):
    async def fake_weather(place):
        return f"Place: {place}\nNow: 24°C, clear sky"
    monkeypatch.setattr(lookups, "weather", fake_weather)
    out = ask("weather in pune")
    assert "24°C" in out["spoken"] and "pune" in out["details"]


def test_failed_lookup_is_reported_not_invented(monkeypatch):
    async def broken(place):
        raise lookups.ToolError("service down")
    monkeypatch.setattr(lookups, "weather", broken)
    assert "LOOKUP FAILED" in ask("weather in pune")["details"]


def test_remember_then_recall():
    assert ask("remember car parked on level 2 pillar b7")["spoken"] == "Got it, I'll remember that."
    out = ask("where is my car parked")
    assert "level 2" in out["details"]


def test_memory_goes_into_the_prompt():
    recall.add("my city is Pune")
    assert "my city is Pune" in recall.prompt()


def test_glasses_photos_are_remembered_for_find_my_stuff():
    import base64
    client.post("/api/ask", headers={"Authorization": f"Bearer {prefs.token()}"},
                json={"text": "what is on the table", "image_b64": base64.b64encode(b"jpeg").decode()})
    seen = [n for n in recall.load() if n["kind"] == "seen"]
    assert seen and "what is on the table" in seen[0]["text"]


def test_timer_is_returned_to_the_phone():
    out = ask("timer 600")
    assert out["timer"] == {"seconds": 600, "label": "test"}
    assert out["spoken"] == "Timer set for 10 minutes."


def test_timer_is_clamped():
    assert ask("timer 1")["timer"]["seconds"] == 5


def test_note_text_cannot_break_out_of_applescript(monkeypatch):
    ran = []
    monkeypatch.setattr(mac, "_run", lambda args: ran.append(args) or "")
    ask('note buy milk" & do shell script "rm -rf ~')
    script = ran[0][2]
    assert 'do shell script' in script  # present only as text...
    assert '\\" & do shell script \\"' in script  # ...because every quote is escaped


def test_speak_endpoint_without_azure_returns_empty(monkeypatch):
    from cj import tts
    monkeypatch.setattr(tts, "available", lambda: False)
    r = client.post("/api/speak", json={"text": "Timer done"}, headers={"Authorization": f"Bearer {prefs.token()}"})
    assert r.status_code == 200 and r.json() == {"voice_b64": ""}
