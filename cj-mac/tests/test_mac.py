import os
import tempfile

os.environ.setdefault("CJ_HOME", tempfile.mkdtemp())
os.environ["CJ_FORCE_MOCK"] = "1"

import pytest
from fastapi.testclient import TestClient

from cj import prefs, mac
from cj.main import app

client = TestClient(app)
APPS = ["Google Chrome", "Safari", "Spotify", "Visual Studio Code", "System Settings", "Notes"]


@pytest.fixture
def ran(monkeypatch):
    """A fake Mac: records every command instead of running it."""
    calls: list[list[str]] = []

    def fake_run(args):
        calls.append(args)
        if args[:2] == ["pmset", "-g"]:
            return "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)\t76%; discharging; 4:12 remaining present: true"
        if "output volume of" in " ".join(args):
            return "40"
        return ""

    monkeypatch.setattr(mac, "_run", fake_run)
    monkeypatch.setattr(mac, "installed_apps", lambda: APPS)
    monkeypatch.setattr(mac, "_pending", None)
    return calls


def say(text):
    r = client.post("/api/ask", json={"text": text}, headers={"Authorization": f"Bearer {prefs.token()}"})
    assert r.status_code == 200, r.text
    return r.json()["spoken"]


@pytest.mark.parametrize("spoken,app_name", [("chrome", "Google Chrome"), ("vs code", "Visual Studio Code"),
                                             ("spotify", "Spotify"), ("settings", "System Settings")])
def test_spoken_app_names_match_installed_apps(monkeypatch, spoken, app_name):
    monkeypatch.setattr(mac, "installed_apps", lambda: APPS)
    assert mac.find_app(spoken) == app_name


def test_unknown_app_is_refused(ran):
    assert "couldn't find" in say("open photoshop")
    assert ran == []


def test_open_app_runs_open_without_a_shell(ran):
    assert say("open chrome") == "Opened Google Chrome."
    assert ran == [["open", "-a", "Google Chrome"]]


def test_quit_waits_for_yes(ran):
    assert "Quit Spotify?" in say("quit spotify")
    assert ran == []
    assert say("yes") == "Quit Spotify."
    assert ran == [["osascript", "-e", 'tell application "Spotify" to quit']]


def test_quit_cancelled_by_no(ran):
    say("quit spotify")
    assert say("no") == "Okay, I won't."
    assert ran == []


def test_confirmation_expires(ran, monkeypatch):
    say("quit spotify")
    name, args, asked = mac._pending
    monkeypatch.setattr(mac, "_pending", (name, args, asked - mac.CONFIRM_SECONDS - 1))
    say("yes")
    assert ran == []


def test_volume_is_clamped(ran):
    assert say("volume 250") == "Volume 100."
    assert ran == [["osascript", "-e", "set volume output volume 100"]]


def test_websites_must_be_web_addresses(ran):
    assert say("go to example.com") == "Opened example.com."
    assert ran == [["open", "https://example.com"]]
    ran.clear()
    say("go to file:///etc/passwd")
    assert ran == []


def test_battery_is_read_out(ran):
    assert say("battery") == "Your Mac is at 76 percent, discharging, 4:12 left."


def test_actions_off_the_list_never_run(ran):
    say("delete everything")
    assert ran == []


def test_screen_description_cannot_trigger_actions(ran, monkeypatch):
    monkeypatch.setattr(mac, "screenshot", lambda: "aGVsbG8=")
    spoken = say("what's on my screen")
    assert spoken  # described by the (mock) model
    assert ran == []  # no command ran while describing the screen


def test_enter_is_refused_in_a_terminal(ran, monkeypatch):
    monkeypatch.setattr(mac, "_frontmost", lambda: "Terminal")
    with pytest.raises(mac.MacError, match="won't press Enter"):
        mac.ACTIONS["press_key"].run({"key": "enter"})
    assert ran == []


def test_keys_come_from_a_fixed_list(ran, monkeypatch):
    monkeypatch.setattr(mac, "_frontmost", lambda: "Safari")
    assert mac.ACTIONS["press_key"].run({"key": "new tab"}) == "Done."
    assert ran == [["osascript", "-e", 'tell application "System Events" to key code 17 using {command down}']]
    with pytest.raises(mac.MacError):
        mac.ACTIONS["press_key"].run({"key": "command option escape"})


def test_typed_text_is_one_escaped_line(ran):
    mac.ACTIONS["type_text"].run({"text": 'echo "hi"\nrm -rf ~'})
    script = ran[0][2]
    assert "\n" not in script and '\\"hi\\"' in script


def test_open_file_only_opens_documents(ran, monkeypatch, tmp_path):
    home = str(tmp_path)
    monkeypatch.setattr(mac.os.path, "expanduser", lambda p: home if p == "~" else p)
    (tmp_path / "resume.pdf").write_text("x")
    (tmp_path / "resume.command").write_text("x")
    monkeypatch.setattr(mac, "_run", lambda args: (ran.append(args), f"{home}/resume.command\n{home}/resume.pdf")[1]
                        if args[0] == "mdfind" else ran.append(args) or "")
    assert mac.ACTIONS["open_file"].run({"name": "resume"}) == "Opened resume.pdf."
    assert ran[-1] == ["open", f"{home}/resume.pdf"]


def test_shortcuts_must_exist(ran, monkeypatch):
    monkeypatch.setattr(mac, "_run", lambda args: (ran.append(args), "Morning Routine\nFocus Mode")[1])
    assert mac.ACTIONS["run_shortcut"].run({"name": "morning routine"}) == "Ran Morning Routine."
    assert ran[-1] == ["shortcuts", "run", "Morning Routine"]
    with pytest.raises(mac.MacError):
        mac.ACTIONS["run_shortcut"].run({"name": "delete everything"})


def test_single_noise_words_are_ignored():
    from cj.modes.assistant import _noise
    assert _noise("battery") and _noise("like")
    assert not _noise("yes") and not _noise("हाँ") and not _noise("open spotify")
