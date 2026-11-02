import base64
import os
import tempfile

os.environ["CJ_HOME"] = tempfile.mkdtemp()
os.environ["CJ_FORCE_MOCK"] = "1"

import pytest
from fastapi.testclient import TestClient

from cj import prefs
from cj.main import app
from cj.modes.common import extract_json

client = TestClient(app)


@pytest.fixture
def auth():
    return {"Authorization": f"Bearer {prefs.token()}"}


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
    from cj.modes.common import persona
    assert "It is now" in persona()


def test_console_websocket_rejects_bad_token():
    with pytest.raises(Exception):
        with client.websocket_connect("/ws/console?token=bad"):
            pass


def test_console_websocket_replays_history(auth):
    client.post("/api/ask", json={"text": "ping"}, headers=auth)
    with client.websocket_connect(f"/ws/console?token={prefs.token()}") as ws:
        kinds = {ws.receive_json()["kind"] for _ in range(3)}
    assert "heard" in kinds


@pytest.mark.parametrize("raw", ['{"a": 1}', '```json\n{"a": 1}\n```', 'Sure! {"a": 1} Hope that helps.'])
def test_extract_json_handles_wrapped_output(raw):
    assert extract_json(raw) == {"a": 1}


def test_extract_json_garbage_gives_empty():
    assert extract_json("no json here") == {}


def test_slow_gemini_models_are_benched_and_tried_last():
    from cj.llm.gemini import Gemini
    g = Gemini()
    g.llm = ["fast", "slow", "spare"]
    g._bench("fast", "test")
    assert g._order() == ["slow", "spare", "fast"]


def test_pairing_prefers_the_wifi_address_over_a_vpn(monkeypatch):
    from cj import pair
    answers = {"en0": "", "en1": "192.168.1.14"}  # en0 unused (no Ethernet), en1 the Wi-Fi

    def fake_run(cmd, **kw):
        class Done:
            stdout = answers.get(cmd[-1], "") + "\n"
        return Done()
    monkeypatch.setattr(pair.shutil, "which", lambda name: "/usr/sbin/ipconfig")
    monkeypatch.setattr(pair.subprocess, "run", fake_run)
    monkeypatch.setattr(pair, "_route_ip", lambda: "10.2.0.2")  # what a VPN tunnel looks like
    assert pair.lan_ip() == "192.168.1.14"
    answers["en1"] = "169.254.3.7"  # self-assigned: not on a network yet
    assert pair.lan_ip() == "10.2.0.2"


def test_pairing_uses_the_host_ip_when_set(monkeypatch):
    from cj import pair
    monkeypatch.setenv("CJ_HOST_IP", "192.168.1.20")  # Docker on a NAS: the container can't see this itself
    monkeypatch.setattr(pair, "_route_ip", lambda: "172.17.0.2")
    assert pair.lan_ip() == "192.168.1.20"
