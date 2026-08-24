#!/usr/bin/env bash
set -euo pipefail

# Backend Long identifiers are serialized as strings. This guard is kept
# intentionally conservative: it flags common direct Number()/parseInt()
# coercions for review, while allowing explicit conversions for counters,
# timestamps and pagination values. Existing call sites can opt out with the
# marker `snowflake-precision-ok` when the value is demonstrably not an id.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_DIR="$ROOT_DIR/mateclaw-ui/src"

if ! command -v rg >/dev/null 2>&1 || [[ ! -d "$UI_DIR" ]]; then
  exit 0
fi

suspects="$(rg -n --glob '*.{ts,vue}' \
  'Number\([^)]*(^|[^[:alnum:]_])(id|.*Id)\b|parseInt\([^)]*(^|[^[:alnum:]_])(id|.*Id)\b' \
  "$UI_DIR" || true)"

if [[ -n "$suspects" ]]; then
  filtered="$(printf '%s\n' "$suspects" | rg -v 'snowflake-precision-ok|Number\(.*(page|size|count|timestamp|Index|index)' || true)"
  if [[ -n "$filtered" ]]; then
    printf '%s\n' 'Snowflake precision review: possible numeric ID coercion(s) found:' >&2
    printf '%s\n' "$filtered" >&2
    printf '%s\n' 'Keep IDs as strings or add a precise snowflake-precision-ok justification.' >&2
    exit 1
  fi
fi

printf '%s\n' 'Snowflake precision check passed.'
