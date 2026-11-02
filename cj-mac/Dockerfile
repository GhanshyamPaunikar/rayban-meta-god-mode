# The CJ brain for Linux machines and NAS boxes (Synology, QNAP, Unraid, a Raspberry Pi).
# Mac-only features (apps, Mail, Calendar, screenshots) are off here; everything else works.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    CJ_HOME=/data

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY cj ./cj
RUN useradd --create-home --uid 1000 cj && mkdir -p /data && chown cj /data
USER cj
VOLUME /data
EXPOSE 8765

# Prints the pairing QR code to the logs, then serves.
CMD ["sh", "-c", "python -m cj.pair && exec uvicorn cj.main:app --host 0.0.0.0 --port 8765"]
