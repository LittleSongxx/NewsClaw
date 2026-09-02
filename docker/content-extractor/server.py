#!/usr/bin/env python3
"""Bounded, fetch-free main-content extraction service for NewsClaw.

The Java server remains the only component allowed to fetch a source URL. This
process receives the already-bounded HTML representation and never performs a
network request itself.
"""

from __future__ import annotations

import hashlib
import importlib.metadata
import json
import os
import threading
from copy import deepcopy
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from trafilatura import bare_extraction
from trafilatura.settings import DEFAULT_CONFIG


HOST = os.getenv("NEWSCLAW_EXTRACTOR_BIND", "0.0.0.0")
PORT = int(os.getenv("NEWSCLAW_EXTRACTOR_PORT", "8090"))
MAX_HTML_BYTES = int(os.getenv("NEWSCLAW_EXTRACTOR_MAX_HTML_BYTES", "1048576"))
MAX_OUTPUT_CHARS = int(os.getenv("NEWSCLAW_EXTRACTOR_MAX_OUTPUT_CHARS", "1048576"))
MAX_CONCURRENCY = int(os.getenv("NEWSCLAW_EXTRACTOR_MAX_CONCURRENCY", "4"))

EXTRACTOR_NAME = "trafilatura"
EXTRACTOR_VERSION = importlib.metadata.version("trafilatura")
EXTRACTION_OPTIONS: dict[str, Any] = {
    # Cross-document deduplication uses process-global caches and makes the
    # same capture depend on earlier requests. Evidence snapshots must be
    # reproducible, so only within-document boilerplate rules are used.
    "deduplicate": False,
    "favor_precision": False,
    "favor_recall": False,
    "include_comments": False,
    "include_formatting": False,
    "include_images": False,
    "include_links": False,
    "include_tables": True,
    "max_tree_size": 200_000,
}
CONFIG_HASH = hashlib.sha256(
    json.dumps(EXTRACTION_OPTIONS, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    .encode("ascii")
).hexdigest()
_CAPACITY = threading.BoundedSemaphore(max(1, MAX_CONCURRENCY))
_TRAFILATURA_OPTIONS = {key: value for key, value in EXTRACTION_OPTIONS.items()
                        if key != "max_tree_size"}
_TRAFILATURA_CONFIG = deepcopy(DEFAULT_CONFIG)
_TRAFILATURA_CONFIG["DEFAULT"]["MAX_TREE_SIZE"] = str(EXTRACTION_OPTIONS["max_tree_size"])


def _json_bytes(value: dict[str, Any]) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def _extract(html: str, url: str | None) -> dict[str, Any]:
    document = bare_extraction(html, url=url, config=_TRAFILATURA_CONFIG,
                               **_TRAFILATURA_OPTIONS)
    if document is None:
        raise ValueError("no main content extracted")
    text = (document.text or "").strip()
    if not text:
        raise ValueError("empty main content extracted")
    if len(text) > MAX_OUTPUT_CHARS:
        raise ValueError("extracted text exceeds output bound")
    title = (document.title or "").strip() or None
    return {
        "text": text,
        "title": title,
        "extractorName": EXTRACTOR_NAME,
        "extractorVersion": EXTRACTOR_VERSION,
        "configHash": CONFIG_HASH,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "NewsClawContentExtractor/1"

    def log_message(self, format: str, *args: Any) -> None:
        # Never log source URLs or HTML. The Java ledger owns request audit data.
        return

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/healthz":
            self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        self._send(
            HTTPStatus.OK,
            {
                "status": "ok",
                "extractorName": EXTRACTOR_NAME,
                "extractorVersion": EXTRACTOR_VERSION,
                "configHash": CONFIG_HASH,
            },
        )

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/extract":
            self._send(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            self._send(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "application_json_required"})
            return
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        # JSON escaping can expand an otherwise bounded HTML body. The decoded
        # HTML receives the authoritative byte-size check below.
        request_limit = MAX_HTML_BYTES * 6 + 65_536
        if length < 0 or length > request_limit:
            self._send(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "request_too_large"})
            return
        try:
            payload = json.loads(self.rfile.read(length))
            html = payload.get("html") if isinstance(payload, dict) else None
            url = payload.get("url") if isinstance(payload, dict) else None
            if not isinstance(html, str) or not html.strip():
                raise ValueError("html must be a non-empty string")
            if len(html.encode("utf-8")) > MAX_HTML_BYTES:
                raise OverflowError("html exceeds byte bound")
            if url is not None and not isinstance(url, str):
                raise ValueError("url must be a string")
        except OverflowError as error:
            self._send(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": str(error)})
            return
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            self._send(HTTPStatus.BAD_REQUEST, {"error": str(error)})
            return

        if not _CAPACITY.acquire(blocking=False):
            self._send(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "capacity_exhausted"})
            return
        try:
            self._send(HTTPStatus.OK, _extract(html, url))
        except ValueError as error:
            self._send(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": str(error)})
        except Exception:
            self._send(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "extraction_failed"})
        finally:
            _CAPACITY.release()

    def _send(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = _json_bytes(payload)
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == "__main__":
    main()
