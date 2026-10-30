"""Checks the offline "CJ" wake word against synthetic speech: does it wake on "CJ" and ignore look-alike sentences?

Needs macOS (it uses `say` voices), the model from scripts/get-wake-model.sh, and:  pip install vosk
Run:  python tools/wake_test.py
Keep GRAMMAR and the matching rule in sync with android-app/.../WakeWord.kt. Last result: 47/51 (misses: one "CJ, ye kya hai?", one "Hey CJ"; false wakes: "Did you see Jake?").
"""
import json
import re
import subprocess
import tempfile
import wave
from pathlib import Path

from vosk import KaldiRecognizer, Model, SetLogLevel

SetLogLevel(-1)
MODEL = Path(__file__).resolve().parent.parent / "android-app/app/src/main/assets/model-en-in"
GRAMMAR = json.dumps(["see jay", "c j", "see", "jay", "she", "sea", "city", "okay", "see you", "say", "j", "g", "hey",
                      "jai", "c e o", "the", "joined", "jake", "seat", "[unk]"])
WAKE = {"see jay", "c j"}
WAKE_RE = re.compile(r"(^|\s)(see jay|c j)(\s|$)")
VOICES = ["Rishi", "Aman", "Tara"]  # Indian English voices that ship with macOS

SHOULD_WAKE = ["CJ", "CJ, what time is it?", "CJ, ye kya hai?", "Hey CJ", "CJ, open Spotify"]
SHOULD_NOT = ["See you later.", "Jay is coming tomorrow.", "She sees the sea.", "The city is far.", "Okay, let's go.",
              "I can see it.", "Hey G", "Say yes.", "Jai ho!", "The CEO joined.", "Did you see Jake?", "Seat number twelve."]


def clip(text: str, voice: str, tmp: Path) -> Path:
    aiff, wav = tmp / "clip.aiff", tmp / "clip.wav"
    subprocess.run(["say", "-v", voice, "-o", str(aiff), text], check=True)
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff), str(wav)], check=True)
    return wav


def wakes(model: Model, wav: Path) -> bool:
    """Same rule as WakeWord.kt: a final result containing the wake word, or a partial ending with it."""
    rec = KaldiRecognizer(model, 16000, GRAMMAR)
    with wave.open(str(wav)) as w:
        while data := w.readframes(2048):
            if rec.AcceptWaveform(data):
                if WAKE_RE.search(json.loads(rec.Result())["text"]):
                    return True
            elif " ".join(json.loads(rec.PartialResult())["partial"].split()[-2:]) in WAKE:
                return True
    return bool(WAKE_RE.search(json.loads(rec.FinalResult())["text"]))


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
