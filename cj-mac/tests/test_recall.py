"""Memory: topics where the newest answer wins, plans that expire, Hindi search, search by meaning, learning."""
import asyncio
import datetime
import json
import os
import tempfile

os.environ.setdefault("CJ_HOME", tempfile.mkdtemp())

import pytest
from fastapi.testclient import TestClient

from cj import prefs, recall
from cj.main import app
from cj.modes import common


@pytest.fixture(autouse=True)
def empty():
    recall.clear()


def test_a_new_note_on_the_same_topic_replaces_the_old_one():
    recall.add("parked on level 3, pillar B12", topic="parking spot")
    recall.add("my locker code is 4412")
    recall.add("parked at B2, near the lifts", topic="Parking spot")
    texts = [n["text"] for n in recall.load()]
    assert "parked at B2, near the lifts" in texts and "parked on level 3, pillar B12" not in texts
    assert "my locker code is 4412" in texts


def test_plans_disappear_after_their_day():
    yesterday = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
    tomorrow = (datetime.date.today() + datetime.timedelta(days=1)).isoformat()
    recall.add("dentist at 5 pm", until=yesterday)
    recall.add("dinner with Priya at 8", until=tomorrow)
    texts = [n["text"] for n in recall.load()]
    assert texts == ["dinner with Priya at 8"]
    assert "(until " in recall.describe(recall.load())


def test_bad_until_dates_are_ignored():
    note = recall.add("call the bank", until="next friday")
    assert "until" not in note and recall.load()[0]["text"] == "call the bank"


def test_hindi_words_are_searchable():
    recall.add("मेरी गाड़ी बी2 पर खड़ी है")
    recall.add("my locker code is 4412")
    assert recall.search("गाड़ी कहाँ है")[0]["text"].startswith("मेरी गाड़ी")


def test_forget_removes_only_clear_matches():
    recall.add("my locker code is 4412")
    recall.add("parked at B2")
    assert recall.forget("locker code") == 1
    assert [n["text"] for n in recall.load()] == ["parked at B2"]


def test_seen_notes_are_not_forgotten_by_voice():
    recall.add("saw my keys on the kitchen counter", kind="seen")
    assert recall.forget("keys") == 0


def test_learned_facts_go_into_the_prompt():
    recall.add("The user's sister lives in Pune", kind="learned")
    assert "(picked up) The user's sister lives in Pune" in recall.prompt()


def test_old_memory_json_is_imported_once():
    home = tempfile.mkdtemp()
    old = os.environ["CJ_HOME"]
    os.environ["CJ_HOME"] = home
    try:
        (prefs.home() / "memory.json").write_text(json.dumps([{"t": "2026-10-01T09:00", "kind": "note", "text": "my city is Pune"}]))
        assert [n["text"] for n in recall.load()] == ["my city is Pune"]
        assert not (prefs.home() / "memory.json").exists()
    finally:
        os.environ["CJ_HOME"] = old


def test_learning_saves_new_facts_with_topics(monkeypatch):
    async def fake_ask_json(task, system, messages, image=None):
        assert task == "learn" and "parked" in messages[0]["text"]
        return {"facts": [{"fact": "The user parked at level 3, pillar B12", "topic": "parking spot", "until": ""},
                          {"fact": "", "topic": ""}, "junk"]}, ""
    monkeypatch.setattr(common, "ask_json", fake_ask_json)
    recall.add("parked at level 1", topic="parking spot")
    saved = asyncio.run(recall.learn("user: I parked at level 3, pillar B12\nassistant: Got it."))
    assert len(saved) == 1
    assert [n["text"] for n in recall.load()] == ["The user parked at level 3, pillar B12"]
    assert recall.load()[0]["kind"] == "learned"


def test_learning_waits_for_something_the_user_said(monkeypatch):
    async def never(*a, **k):
        raise AssertionError("no model call for an assistant-only transcript")
    monkeypatch.setattr(common, "ask_json", never)
    learner = recall.Learner()
    learner.lines = ["assistant: Hello!"]
    assert asyncio.run(learner.flush()) == []


def test_search_by_meaning_finds_other_wordings(monkeypatch):
    recall.add("parked on level 3, pillar B12")
    recall.add("my locker code is 4412")
    vectors = {"meri gaadi kahan hai?": [1.0, 0.0], "parked on level 3, pillar B12": [0.9, 0.1],
               "my locker code is 4412": [0.0, 1.0]}

    async def fake_embed(texts):
        return [vectors[t] for t in texts]
    monkeypatch.setattr(recall, "_embed", fake_embed)
    monkeypatch.setattr(prefs, "key", lambda p: "test-key")
    found = asyncio.run(recall.find("meri gaadi kahan hai?"))
    assert found[0]["text"] == "parked on level 3, pillar B12"
    assert all(n["text"] != "my locker code is 4412" for n in found)


def test_search_by_meaning_falls_back_to_words_when_offline(monkeypatch):
    recall.add("my locker code is 4412")

    async def offline(texts):
        raise OSError("no network")
    monkeypatch.setattr(recall, "_embed", offline)
    monkeypatch.setattr(prefs, "key", lambda p: "test-key")
    assert asyncio.run(recall.find("locker code"))[0]["text"] == "my locker code is 4412"


def test_notes_can_be_edited_and_deleted_from_the_app():
    client = TestClient(app)
    auth = {"Authorization": f"Bearer {prefs.token()}"}
    note = recall.add("my city is Pune")
    assert client.patch(f"/api/memory/{note['id']}", json={"text": "my city is Mumbai"}, headers=auth).status_code == 200
    assert client.get("/api/memory", headers=auth).json()["notes"][0]["text"] == "my city is Mumbai"
    assert client.patch(f"/api/memory/{note['id']}", json={"text": " "}, headers=auth).status_code == 400
    assert client.delete(f"/api/memory/{note['id']}", headers=auth).status_code == 200
    assert client.delete(f"/api/memory/{note['id']}", headers=auth).status_code == 404
    assert client.delete("/api/memory/1", headers={"Authorization": "Bearer wrong"}).status_code == 401
