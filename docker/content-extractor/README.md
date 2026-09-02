# NewsClaw content extractor

This is the fetch-free main-content adapter used by AI-news evidence capture.
The Java service remains the only URL-fetching authority and applies the
existing redirect, SSRF, byte-size, and timeout controls. This container only
accepts already-fetched HTML at `POST /v1/extract`.

The implementation is pinned to
[Trafilatura 2.2.0](https://trafilatura.readthedocs.io/en/latest/corefunctions.html).
Every successful response carries the extractor name, package version, and a
SHA-256 of the behavior-affecting options. Cross-document deduplication is
disabled because its process-global cache would make evidence text depend on
request order.

## API

```text
GET  /healthz
POST /v1/extract
Content-Type: application/json
{"html":"<html>...</html>","url":"https://publisher.example/story"}
```

The `url` is context for relative links and extraction metadata; the adapter
does not dereference it. Request HTML, decoded output, concurrency, and Java
response sizes are independently bounded. The service does not log HTML or
source URLs.

## Reproducibility and operation

- The Python base image is digest-pinned; dependencies and their distribution
  hashes are frozen in `requirements.lock`.
- The image runs as UID 10001. Compose additionally uses a read-only root
  filesystem, a bounded tmpfs, no Linux capabilities, no-new-privileges, PID
  and memory limits, and does not publish the port to the host. It is attached
  only to a dedicated Docker `internal` network shared with the Java service,
  so the runtime has no normal internet-egress path.
- Compose enables the primary extractor and requires it. A plain standalone
  JAR keeps it disabled unless an operator configures the endpoint. An enabled,
  required extractor fails evidence capture closed when unavailable.

Regenerate the lock only as an explicit dependency upgrade, then rerun both
evaluation tracks and update the admitted version/configuration provenance:

```bash
uv pip compile --python-version 3.12 --generate-hashes \
  --no-annotate --no-header \
  --output-file docker/content-extractor/requirements.lock \
  docker/content-extractor/requirements.in
```

Run the repository-owned domain-layout gate against a local adapter:

```bash
python3 scripts/eval-ai-news-content-extraction.py \
  --endpoint http://127.0.0.1:8090 \
  --output target/ai-news-content-extraction-business-v1.json
```

The optional WebMainBench diagnostic uses a fixed dataset revision and
SHA-256. It is intentionally not a production-quality or AI-news-recall claim:

```bash
benchmark="$(./scripts/download-ai-news-content-extraction-benchmark.sh)"
python3 scripts/eval-ai-news-content-extraction.py \
  --endpoint http://127.0.0.1:8090 \
  --webmainbench "${benchmark}" \
  --output target/ai-news-content-extraction-webmainbench-545.json
```

Trafilatura and the local adapter are Apache-2.0 licensed. The synthetic
business fixtures contain no publisher page content. WebMainBench is downloaded
only into the ignored evaluation cache and is not redistributed by NewsClaw.
