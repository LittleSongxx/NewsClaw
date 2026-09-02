#!/usr/bin/env python3
"""Deterministic business and external diagnostics for AI-news body extraction."""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import sys
import unicodedata
import urllib.error
import urllib.request
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / (
    "newsclaw-server/src/test/resources/evals/ai-news/content-extraction/manifest-v1.json"
)
WEBMAINBENCH_545_SHA256 = "0efaa4b49a45e320a27fe6e5a0b6aad5b57259fc3321ac3448519cacc74c537e"


class WholeDocumentTextParser(HTMLParser):
    """Approximation of the former Jsoup document.text() compatibility path."""

    ignored_tags = {"script", "style", "noscript", "template", "svg", "canvas"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ignored_depth = 0
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() in self.ignored_tags:
            self.ignored_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if self.ignored_depth and tag.lower() in self.ignored_tags:
            self.ignored_depth -= 1

    def handle_data(self, data: str) -> None:
        if not self.ignored_depth and data.strip():
            self.parts.append(data)


def whole_document_text(html: str) -> str:
    parser = WholeDocumentTextParser()
    try:
        parser.feed(html or "")
        parser.close()
    except Exception:
        pass
    return normalized(" ".join(parser.parts))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalized(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value or "").split())


def alphanumeric_characters(value: str) -> Counter[str]:
    return Counter(character.lower() for character in unicodedata.normalize("NFKC", value or "")
                   if character.isalnum())


def bag_f1(prediction: str, reference: str) -> tuple[float, float, float]:
    predicted = alphanumeric_characters(prediction)
    expected = alphanumeric_characters(reference)
    if not expected:
        return (1.0, 1.0, 1.0) if not predicted else (0.0, 1.0, 0.0)
    if not predicted:
        return 0.0, 0.0, 0.0
    overlap = sum((predicted & expected).values())
    precision = overlap / sum(predicted.values())
    recall = overlap / sum(expected.values())
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1


def extract(endpoint: str, html: str, url: str, timeout: float) -> dict[str, Any]:
    payload = json.dumps({"html": html, "url": url}, ensure_ascii=False,
                         separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        endpoint.rstrip("/") + "/v1/extract",
        data=payload,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        result = json.loads(response.read())
    required = ("text", "extractorName", "extractorVersion", "configHash")
    if not isinstance(result, dict) or any(not result.get(field) for field in required):
        raise ValueError("extractor returned an incomplete response")
    return result


def business_evaluation(endpoint: str, manifest_path: Path, timeout: float) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected_implementation = manifest.get("expectedImplementation")
    if not isinstance(expected_implementation, dict) or any(
            not isinstance(expected_implementation.get(field), str)
            or not expected_implementation[field]
            for field in ("name", "version", "configHash")):
        raise ValueError("manifest expectedImplementation is incomplete")
    outcomes = []
    required_total = required_found = forbidden_total = forbidden_absent = 0
    baseline_required_found = baseline_forbidden_absent = 0
    repeat_stable = 0
    implementations: set[tuple[str, str, str]] = set()
    for case in manifest["cases"]:
        html_path = manifest_path.parent / case["htmlFile"]
        html = html_path.read_text(encoding="utf-8")
        baseline_text = whole_document_text(html)
        baseline_required_found += sum(normalized(snippet) in baseline_text
                                       for snippet in case["requiredSnippets"])
        baseline_forbidden_absent += sum(normalized(snippet) not in baseline_text
                                         for snippet in case["forbiddenSnippets"])
        try:
            result = extract(endpoint, html, case["sourceUrl"], timeout)
            repeated = extract(endpoint, html, case["sourceUrl"], timeout)
            stable = all(result.get(field) == repeated.get(field) for field in (
                "text", "title", "extractorName", "extractorVersion", "configHash"
            ))
            approved = (
                result.get("extractorName") == expected_implementation["name"]
                and result.get("extractorVersion") == expected_implementation["version"]
                and result.get("configHash") == expected_implementation["configHash"]
            )
            repeat_stable += int(stable)
            text = normalized(result["text"])
            missing = [snippet for snippet in case["requiredSnippets"]
                       if normalized(snippet) not in text]
            leaked = [snippet for snippet in case["forbiddenSnippets"]
                      if normalized(snippet) in text]
            required_total += len(case["requiredSnippets"])
            required_found += len(case["requiredSnippets"]) - len(missing)
            forbidden_total += len(case["forbiddenSnippets"])
            forbidden_absent += len(case["forbiddenSnippets"]) - len(leaked)
            implementations.add((result["extractorName"], result["extractorVersion"],
                                 result["configHash"]))
            outcomes.append({
                "id": case["id"],
                "status": ("pass" if not missing and not leaked and stable and approved
                           else "fail"),
                "missingRequired": missing,
                "leakedBoilerplate": leaked,
                "repeatStable": stable,
                "provenanceApproved": approved,
                "textLength": len(result["text"]),
                "fixtureSha256": sha256_file(html_path),
            })
        except Exception as error:  # the report needs the failed case, not a traceback
            required_total += len(case["requiredSnippets"])
            forbidden_total += len(case["forbiddenSnippets"])
            outcomes.append({"id": case["id"], "status": "error",
                             "error": type(error).__name__})
    return {
        "datasetId": manifest["datasetId"],
        "datasetVersion": manifest["datasetVersion"],
        "manifestSha256": sha256_file(manifest_path),
        "expectedImplementation": expected_implementation,
        "cases": len(outcomes),
        "passedCases": sum(item["status"] == "pass" for item in outcomes),
        "repeatDeterminismRate": repeat_stable / len(outcomes) if outcomes else 1.0,
        "requiredSnippetRecall": required_found / required_total if required_total else 1.0,
        "boilerplateExclusionRate": forbidden_absent / forbidden_total if forbidden_total else 1.0,
        "compatibilityBaseline": {
            "name": "whole-document-text-approximation-v1",
            "requiredSnippetRecall": (baseline_required_found / required_total
                                      if required_total else 1.0),
            "boilerplateExclusionRate": (baseline_forbidden_absent / forbidden_total
                                         if forbidden_total else 1.0),
        },
        "implementations": [
            {"name": name, "version": version, "configHash": config_hash}
            for name, version, config_hash in sorted(implementations)
        ],
        "outcomes": outcomes,
    }


def nested_string(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    candidate = value
    if candidate.startswith('"'):
        try:
            decoded = json.loads(candidate)
            if isinstance(decoded, str):
                candidate = decoded
        except json.JSONDecodeError:
            pass
    return candidate


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = round((len(ordered) - 1) * fraction)
    return ordered[index]


def external_evaluation(endpoint: str, dataset_path: Path, timeout: float,
                        limit: int | None) -> dict[str, Any]:
    actual_hash = sha256_file(dataset_path)
    if actual_hash != WEBMAINBENCH_545_SHA256:
        raise ValueError(f"WebMainBench checksum mismatch: {actual_hash}")
    metrics: list[tuple[float, float, float]] = []
    paired_baseline_metrics: list[tuple[float, float, float]] = []
    all_baseline_metrics: list[tuple[float, float, float]] = []
    failures: list[dict[str, Any]] = []
    versions: set[tuple[str, str, str]] = set()
    processed = 0
    with dataset_path.open(encoding="utf-8") as stream:
        for line in stream:
            if limit is not None and processed >= limit:
                break
            row = json.loads(line)
            html = nested_string(row.get("html"))
            reference = nested_string(row.get("groundtruth_content"))
            if not html or not reference:
                continue
            processed += 1
            baseline_metric = bag_f1(whole_document_text(html), reference)
            all_baseline_metrics.append(baseline_metric)
            try:
                result = extract(endpoint, html, row.get("url") or "", timeout)
                metrics.append(bag_f1(result["text"], reference))
                paired_baseline_metrics.append(baseline_metric)
                versions.add((result["extractorName"], result["extractorVersion"],
                              result["configHash"]))
            except Exception as error:
                error_name = (f"HTTPError_{error.code}"
                              if isinstance(error, urllib.error.HTTPError)
                              else type(error).__name__)
                failures.append({"trackId": str(row.get("track_id", "")),
                                 "error": error_name,
                                 "htmlBytes": len(html.encode("utf-8"))})
    precision = [item[0] for item in metrics]
    recall = [item[1] for item in metrics]
    f1 = [item[2] for item in metrics]
    paired_baseline_f1 = [item[2] for item in paired_baseline_metrics]
    all_baseline_f1 = [item[2] for item in all_baseline_metrics]
    return {
        "datasetId": "opendatalab/WebMainBench_545",
        "datasetRevision": "5da0972e9b58d0c7891ae75053ced97c268f52e3",
        "datasetSha256": actual_hash,
        "metric": "unicode-alphanumeric-bag-f1-diagnostic-v1",
        "metricWarning": "Not the official WebMainBench fine-grained leaderboard metric.",
        "processed": processed,
        "successful": len(metrics),
        "failed": len(failures),
        "meanPrecision": statistics.fmean(precision) if precision else None,
        "meanRecall": statistics.fmean(recall) if recall else None,
        "meanF1": statistics.fmean(f1) if f1 else None,
        "p10F1": percentile(f1, 0.10),
        "medianF1": percentile(f1, 0.50),
        "compatibilityBaseline": {
            "name": "whole-document-text-approximation-v1",
            "meanF1AllInputs": (statistics.fmean(all_baseline_f1)
                                if all_baseline_f1 else None),
            "meanF1PairedSuccessfulInputs": (statistics.fmean(paired_baseline_f1)
                                             if paired_baseline_f1 else None),
            "meanF1DeltaOnPairedInputs": (
                statistics.fmean(f1) - statistics.fmean(paired_baseline_f1)
                if f1 and paired_baseline_f1 else None
            ),
            "warning": "Approximation of Jsoup whole-document text, not the Java implementation itself."
        },
        "implementations": [
            {"name": name, "version": version, "configHash": config_hash}
            for name, version, config_hash in sorted(versions)
        ],
        "failures": failures[:50],
    }


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://127.0.0.1:8090")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--webmainbench", type=Path)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(list(argv) if argv is not None else None)

    report: dict[str, Any] = {
        "schemaVersion": "1.0",
        "business": business_evaluation(args.endpoint, args.manifest, args.timeout),
    }
    if args.webmainbench:
        report["external"] = external_evaluation(
            args.endpoint, args.webmainbench, args.timeout, args.limit)
    serialized = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    print(serialized, end="")
    business = report["business"]
    return 0 if (business["passedCases"] == business["cases"]
                 and business["requiredSnippetRecall"] == 1.0
                 and business["boilerplateExclusionRate"] == 1.0) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, urllib.error.URLError) as error:
        print(f"content-extraction evaluation failed: {error}", file=sys.stderr)
        raise SystemExit(2)
