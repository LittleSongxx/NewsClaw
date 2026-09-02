#!/usr/bin/env bash
set -euo pipefail

revision="5da0972e9b58d0c7891ae75053ced97c268f52e3"
expected_sha256="0efaa4b49a45e320a27fe6e5a0b6aad5b57259fc3321ac3448519cacc74c537e"
cache_dir="${NEWSCLAW_EVAL_CACHE_DIR:-target/eval-cache/ai-news-content-extraction}"
target="${cache_dir}/WebMainBench_545-${revision}.jsonl"
url="https://huggingface.co/datasets/opendatalab/WebMainBench/resolve/${revision}/WebMainBench_545.jsonl"

mkdir -p "${cache_dir}"
if [[ -f "${target}" ]] && [[ "$(sha256sum "${target}" | awk '{print $1}')" == "${expected_sha256}" ]]; then
  printf '%s\n' "${target}"
  exit 0
fi

temporary="$(mktemp -p "${cache_dir}" webmainbench.XXXXXX)"
trap 'rm -f "${temporary}"' EXIT
curl --fail --location --retry 3 --output "${temporary}" "${url}"
actual_sha256="$(sha256sum "${temporary}" | awk '{print $1}')"
if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
  printf 'checksum mismatch: expected %s, got %s\n' "${expected_sha256}" "${actual_sha256}" >&2
  exit 1
fi
mv "${temporary}" "${target}"
trap - EXIT
printf '%s\n' "${target}"
