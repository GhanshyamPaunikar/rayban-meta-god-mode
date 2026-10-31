"""Renders the same lines in several Azure (Microsoft) female voices so you can pick CJ's voice.
Run from cj-mac/:  .venv/bin/python ../tools/azure_voice_samples.py   -> CJ/voices/azure/index.html
Uses the Azure key in ~/.cj/config.json (about 3,000 characters of your monthly allowance)."""
import html
import sys
import time
from pathlib import Path
from xml.sax.saxutils import escape

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "brain"))
from app import config  # noqa: E402

OUT = Path(__file__).resolve().parent.parent / "voices" / "azure"
ENGLISH = "Hi, I'm CJ. It's a quarter past five in London right now, and it looks like rain this evening."
HINGLISH = "Kal Delhi mein mausam saaf rahega, lekin shaam ko thodi thandak hogi."
HINDI = "नमस्ते, मैं जी हूँ। आज मौसम साफ़ रहेगा, लेकिन शाम को थोड़ी ठंडक होगी।"

VOICES = [  # (voice, description, lines to read)
    ("en-US-Olivia:MAI-Voice-2.1-Flash", "Olivia · American · MAI (CJ's default voice)", "eg"),
    ("en-US-Harper:MAI-Voice-2.1-Flash", "Harper · American · MAI, very expressive", "eg"),
    ("en-US-Iris:MAI-Voice-2.1-Flash", "Iris · American · MAI", "eg"),
    ("en-IN-Priya:MAI-Voice-2.1-Flash", "Priya · Indian English · MAI", "eg"),
    ("en-IN-Neerja:DragonHDLatestNeural", "Neerja HD · Indian English · high definition", "eg"),
    ("en-IN-Aarti:DragonHDLatestNeural", "Aarti HD · Indian English · high definition", "eg"),
    ("hi-IN-Kavya:MAI-Voice-2.1-Flash", "Kavya · Hindi · MAI", "hg"),
    ("hi-IN-Priya:MAI-Voice-2.1-Flash", "Priya · Hindi · MAI", "hg"),
    ("hi-IN-SwaraNeural", "Swara · Hindi (current Hindi fallback)", "hg"),
    ("en-US-AvaMultilingualNeural", "Ava · American · multilingual", "eg"),
    ("en-US-JennyNeural", "Jenny · American", "eg"),
    ("en-GB-SoniaNeural", "Sonia · British", "eg"),
]
LINES = {"e": ("English", ENGLISH, "en-US"), "g": ("Hinglish", HINGLISH, "en-IN"), "h": ("Hindi", HINDI, "hi-IN")}


def synth(client: httpx.Client, voice: str, text: str, lang: str) -> bytes:
    ssml = (f'<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="{lang}">'
            f'<voice name="{voice}">{escape(text)}</voice></speak>')
    r = client.post(f"https://{config.load()['azure_region']}.tts.speech.microsoft.com/cognitiveservices/v1",
                    content=ssml.encode(), headers={"Ocp-Apim-Subscription-Key": config.key("azure_speech"),
                                                    "Content-Type": "application/ssml+xml",
                                                    "X-Microsoft-OutputFormat": "riff-24khz-16bit-mono-pcm",
                                                    "User-Agent": "CJ"})
    r.raise_for_status()
    return r.content


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    with httpx.Client(timeout=30) as client:
        for voice, note, lines in VOICES:
            cells = []
            for code in lines:
                label, text, lang = LINES[code]
                started = time.monotonic()
                try:
                    audio = synth(client, voice, text, lang)
                except httpx.HTTPError as e:
                    print(f"{voice:40} {label:8} FAILED {e}")
                    continue
                took = time.monotonic() - started
                name = f"{voice.split(':')[0]}-{voice.split(':')[-1][:8]}-{label.lower()}.wav".replace(" ", "")
                (OUT / name).write_bytes(audio)
                cells.append(f'<div class="clip"><span>{label}</span><audio controls preload="none" src="{name}"></audio>'
                             f'<small>ready in {took:.1f} s</small></div>')
                print(f"{voice:40} {label:8} {took:4.2f}s")
            rows.append(f'<section><h3>{html.escape(note)}</h3><code>{html.escape(voice)}</code>{"".join(cells)}</section>')
    page = (Path(__file__).with_name("voice_page.html").read_text()
            .replace("ROWS", "\n".join(rows)).replace("EN", html.escape(ENGLISH))
            .replace("HG", html.escape(HINGLISH)).replace("HI", html.escape(HINDI)))
    (OUT / "index.html").write_text(page)
    print(f"\nOpen {OUT / 'index.html'}")


if __name__ == "__main__":
    main()
