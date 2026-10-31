#!/usr/bin/env bash
# Downloads the offline wake-word model (Vosk small Indian English, ~37 MB, Apache 2.0) into the app's assets.
# Run once before building the Android app.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=cj-android/app/src/main/assets/model-en-in
if [ -f "$DEST/uuid" ]; then echo "Wake-word model already in $DEST"; exit 0; fi
TMP=$(mktemp -d)
curl -fSL -o "$TMP/model.zip" https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip
unzip -q "$TMP/model.zip" -d "$TMP"
mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
mv "$TMP/vosk-model-small-en-in-0.4" "$DEST"
echo "vosk-model-small-en-in-0.4" > "$DEST/uuid"
rm -rf "$TMP"
echo "Wake-word model ready in $DEST"
