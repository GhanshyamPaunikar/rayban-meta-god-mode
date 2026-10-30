"""CJ's voice: Microsoft Azure Speech (Olivia by default; pick another with tools/azure_voice_samples.py).

Needs the "azure_speech" key and "azure_region" in ~/.cj/config.json. Without them, or when Azure fails, the
phone falls back to its own text-to-speech.
"""
from __future__ import annotations

import os
import re
from xml.sax.saxutils import escape

import httpx

from . import config

VOICE = os.environ.get("CJ_VOICE", "en-US-Olivia:MAI-Voice-2.1-Flash")  # natural and expressive; set CJ_VOICE to change it
HINDI_VOICE = os.environ.get("CJ_VOICE_HINDI", "hi-IN-SwaraNeural")  # for answers in Devanagari


def available() -> bool:
    return bool(config.key("azure_speech") and config.load().get("azure_region"))


async def speak(text: str) -> bytes | None:
    """24 kHz 16-bit mono WAV of [text], or None if Azure isn't set up or fails."""
    if not text.strip() or not available():
        return None
    hindi = bool(re.search(r"[ऀ-ॿ]", text))
    voice, lang = (HINDI_VOICE, "hi-IN") if hindi else (VOICE, "en-US")
    ssml = (f'<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="{lang}">'
            f'<voice name="{voice}">{escape(text[:2000])}</voice></speak>')
    region = config.load()["azure_region"]
    try:
        async with httpx.AsyncClient(timeout=20) as client:
            r = await client.post(f"https://{region}.tts.speech.microsoft.com/cognitiveservices/v1",
                                  content=ssml.encode(), headers={
                                      "Ocp-Apim-Subscription-Key": config.key("azure_speech"),
                                      "Content-Type": "application/ssml+xml",
                                      "X-Microsoft-OutputFormat": "riff-24khz-16bit-mono-pcm",
                                      "User-Agent": "CJ"})
    except httpx.HTTPError:
        return None
    return r.content if r.status_code == 200 else None
