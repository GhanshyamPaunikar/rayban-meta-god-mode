import os
import tempfile

os.environ.setdefault("CJ_HOME", tempfile.mkdtemp())
os.environ["CJ_FORCE_MOCK"] = "1"

import pytest
from fastapi.testclient import TestClient

from cj import access, prefs
from cj.main import app
from cj.modes.common import persona

client = TestClient(app)


def test_no_settings_adds_nothing():
    assert access.prompt({}) == ""
    assert access.prompt(None) == ""  # nothing set for this request


def test_each_switch_adds_its_rule():
    text = access.prompt({"read_aloud": True, "money": True, "detail": "short"})
    assert "word for word" in text and "total" in text and "one short sentence" in text
    assert "medicine" not in text.lower()


def test_answer_instructions_carry_the_settings():
    from cj.modes.assistant import ANSWER, _system
    token = access.SETTINGS.set({"medicine": True})
    try:
        system = _system(ANSWER)
        assert "expiry date" in system
        assert system.rstrip().endswith(access.RULES["medicine"])  # last, so it beats the length rules
    finally:
        access.SETTINGS.reset(token)
    assert "expiry date" not in _system(ANSWER)


def test_slow_speech():
    assert access.slow_speech({"slow_speech": True}) and not access.slow_speech({})


def test_ask_accepts_settings_and_rejects_bad_ones():
    auth = {"Authorization": f"Bearer {prefs.token()}"}
    ok = client.post("/api/ask", json={"text": "hi", "access": {"read_aloud": True, "detail": "detailed"}}, headers=auth)
    assert ok.status_code == 200 and ok.json()["spoken"]
    bad = client.post("/api/ask", json={"text": "hi", "access": {"detail": "whatever"}}, headers=auth)
    assert bad.status_code == 422


def test_a_lone_call_for_help_is_not_noise():
    from cj.modes.assistant import _noise
    for said in ["Help!", "SOS", "Emergency.", "Bachao", "बचाओ"]:
        assert not _noise(said), said
    assert _noise("battery")  # other lone words still are
