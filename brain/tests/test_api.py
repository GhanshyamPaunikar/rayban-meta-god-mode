import base64
import os
import tempfile

os.environ["CJ_HOME"] = tempfile.mkdtemp()
os.environ["CJ_FORCE_MOCK"] = "1"

import pytest
from fastapi.testclient import TestClient

from app import config
from app.main import app
from app.modes.common import extract_json

client = TestClient(app)


@pytest.fixture
def auth():
    return {"Authorization": f"Bearer {config.token()}"}


def test_health_is_open():
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["name"] == "CJ"


def test_ask_requires_token():
    assert client.post("/api/ask", json={"text": "hi"}).status_code == 401
    assert client.post("/api/ask", json={"text": "hi"}, headers={"Authorization": "Bearer nope"}).status_code == 401


def test_ask_english_and_hindi(auth):
    en = client.post("/api/ask", json={"text": "what time is it"}, headers=auth).json()
    hi = client.post("/api/ask", json={"text": "ये क्या है?"}, headers=auth).json()
    assert en["language"] == "en" and en["spoken"]
    assert hi["language"] == "hi"


def test_ask_validates_input(auth):
    assert client.post("/api/ask", json={"text": ""}, headers=auth).status_code == 422


def _clip(words: str) -> str:
    # The mock provider "transcribes" a clip by decoding its bytes, so tests can choose what was said.
    return base64.b64encode(words.encode()).decode()


def test_spoken_question_is_transcribed_and_answered(auth):
    out = client.post("/api/ask", json={"audio_b64": _clip("what time is it in London")}, headers=auth).json()
    assert out["heard"] == "what time is it in London"
    assert out["needs_photo"] is False and out["spoken"]


def test_spoken_look_question_asks_for_a_photo_first(auth):
    out = client.post("/api/ask", json={"audio_b64": _clip("ye kya hai, look at this")}, headers=auth).json()
    assert out["needs_photo"] is True and out["spoken"] == ""


def test_typed_answer_reports_no_photo_needed(auth):
    out = client.post("/api/ask", json={"text": "hello"}, headers=auth).json()
    assert out["heard"] == "hello" and out["needs_photo"] is False


def test_prompt_carries_current_time():
    from app.modes.common import persona
    assert "It is now" in persona()


def test_dashboard_websocket_rejects_bad_token():
    with pytest.raises(Exception):
        with client.websocket_connect("/ws/dashboard?token=bad"):
            pass


def test_dashboard_websocket_replays_history(auth):
    client.post("/api/ask", json={"text": "ping"}, headers=auth)
    with client.websocket_connect(f"/ws/dashboard?token={config.token()}") as ws:
        kinds = {ws.receive_json()["kind"] for _ in range(3)}
    assert "heard" in kinds


@pytest.mark.parametrize("raw", ['{"a": 1}', '```json\n{"a": 1}\n```', 'Sure! {"a": 1} Hope that helps.'])
def test_extract_json_handles_wrapped_output(raw):
    assert extract_json(raw) == {"a": 1}


def test_extract_json_garbage_gives_empty():
    assert extract_json("no json here") == {}


def test_slow_gemini_models_are_benched_and_tried_last():
    from app.models.gemini import Gemini
    g = Gemini()
    g.models = ["fast", "slow", "spare"]
    g._bench("fast", "test")
    assert g._order() == ["slow", "spare", "fast"]
