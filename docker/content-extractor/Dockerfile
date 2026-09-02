FROM python:3.12-slim-bookworm@sha256:0f5b26b9518d002b6173fd61daad821fa340635ebfec5bba471013f9ca114579

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    NEWSCLAW_EXTRACTOR_PORT=8090

WORKDIR /app
COPY requirements.lock ./requirements.lock
RUN pip install --no-cache-dir --require-hashes -r requirements.lock \
    && useradd --create-home --uid 10001 extractor
COPY --chown=extractor:extractor server.py ./server.py

USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["python", "/app/server.py"]
