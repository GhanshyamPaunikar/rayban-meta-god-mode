"""Checks the offline "CJ" wake word against synthetic speech: does it wake on "CJ" and ignore look-alike sentences?

Needs macOS (it uses `say` voices), the model from scripts/get-wake-model.sh, and:  pip install vosk
Run:  python tools/wake_test.py
Keep GRAMMAR and the matching rule in sync with cj-android/.../Hotword.kt. Last result: 51/51, twice (the old rule, which also woke on partial matches mid-sentence, scored 47/51).
"""
import json
import re
import subprocess
import tempfile
import wave
from pathlib import Path

from vosk import KaldiRecognizer, Model, SetLogLevel

SetLogLevel(-1)
MODEL = Path(__file__).resolve().parent.parent / "cj-android/app/src/main/assets/model-en-in"
GRAMMAR = json.dumps(["see jay", "c j", "see", "jay", "she", "sea", "city", "okay", "see you", "say", "j", "g", "hey",
                      "jai", "c e o", "the", "joined", "jake", "seat", "[unk]"])
PAIRS = {("see", "jay"), ("c", "j")}
CALL = {"hey", "okay"}  # words that may come right before the name
MIN_CONF = 0.4          # settled results: the lowest real "CJ" in this test scored 0.49
VOICES = ["Rishi", "Aman", "Tara"]  # Indian English voices that ship with macOS

SHOULD_WAKE = ["CJ", "CJ, what time is it?", "CJ, ye kya hai?", "Hey CJ", "CJ, open Spotify"]
SHOULD_NOT = ["See you later.", "Jay is coming tomorrow.", "She sees the sea.", "The city is far.", "Okay, let's go.",
              "I can see it.", "Hey G", "Say yes.", "Jai ho!", "The CEO joined.", "Did you see Jake?", "Seat number twelve."]


def clip(text: str, voice: str, tmp: Path) -> Path:
    aiff, wav = tmp / "clip.aiff", tmp / "clip.wav"
    subprocess.run(["say", "-v", voice, "-o", str(aiff), text], check=True)
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff), str(wav)], check=True)
    return wav


def called(words: list[dict]) -> bool:
    """Same rule as Hotword.kt: "see jay" / "c j" at the start of what was said or right after "hey" / "okay", and
    (in settled results) recognised with some confidence. "Did you see Jake?" decodes as "you c j" and is ignored."""
    for i in range(len(words) - 1):
        a, b = words[i], words[i + 1]
        if (a["word"], b["word"]) not in PAIRS:
            continue
        if "conf" in a and min(a["conf"], b["conf"]) < MIN_CONF:
            continue
        before = words[i - 1] if i else None
        if before is None or before["word"] in CALL:
            return True
    return False


def wakes(model: Model, wav: Path) -> bool:
    rec = KaldiRecognizer(model, 16000, GRAMMAR)
    rec.SetWords(True)
    with wave.open(str(wav)) as w:
        while data := w.readframes(2048):
            if rec.AcceptWaveform(data):
                if called(json.loads(rec.Result()).get("result", [])):
                    return True
            elif called([{"word": x} for x in json.loads(rec.PartialResult())["partial"].split()]):
                return True
    return called(json.loads(rec.FinalResult()).get("result", []))


def main() -> None:
    model = Model(str(MODEL))
    correct = total = 0
    with tempfile.TemporaryDirectory() as tmp:
        for voice in VOICES:
            for text in SHOULD_WAKE + SHOULD_NOT:
                expected = text in SHOULD_WAKE
                got = wakes(model, clip(text, voice, Path(tmp)))
                correct += got == expected
                total += 1
                if got != expected:
                    print(f"miss  {voice:5}  {'should wake' if expected else 'false wake '}  {text}")
    print(f"\n{correct}/{total} correct")


if __name__ == "__main__":
    main()
