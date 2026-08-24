#!/usr/bin/env bash
set -euo pipefail

# Audit the tracked product surface without rewriting preserved legal and
# historic migration material. The legacy needles are assembled so this audit
# itself does not introduce an accidental old product marker.
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"

legacy_lower="mate""claw"
legacy_title="Mate""Claw"
legacy_upper="MATE""CLAW"
legacy_camel="mate""Claw"
legacy_package="vip.""mate"
legacy_spaced="Mate"" Claw"
legacy_property="mate""."
legacy_domain="mate"".example.com"
legacy_api_domain="api.""mate"".vip"
legacy_claw_domain="claw.""mate"".vip"
legacy_property_pattern="(^|[^[:alnum:]_])${legacy_property//./\\.}"
needle_pattern="${legacy_lower}|${legacy_title}|${legacy_upper}|${legacy_camel}|${legacy_package//./\\.}|${legacy_spaced}|${legacy_property_pattern}|${legacy_domain//./\\.}|${legacy_api_domain//./\\.}|${legacy_claw_domain//./\\.}"

is_preserved_file() {
  local path="$1"
  case "$path" in
    LICENSE)
      return 0
      ;;
    newsclaw-server/src/main/resources/db/migration/*/V*__*.sql)
      local filename version
      filename="${path##*/}"
      version="${filename#V}"
      version="${version%%__*}"
      [[ "$version" =~ ^[0-9]+$ && "$version" -le 198 ]]
      return
      ;;
  esac
  return 1
}

failed=0
while IFS= read -r -d '' path; do
  if is_preserved_file "$path" || [[ ! -f "$path" ]]; then
    continue
  fi

  if [[ "$path" =~ $needle_pattern ]]; then
    printf 'legacy product marker in path: %s\n' "$path" >&2
    failed=1
  fi

  if grep -Iq . "$path" && grep -Ein "$needle_pattern" "$path"; then
    printf 'legacy product marker in content: %s\n' "$path" >&2
    failed=1
  fi
done < <(git ls-files -co --exclude-standard -z)

if (( failed )); then
  printf 'Brand audit failed. Allowed exceptions are LICENSE and Flyway V1-V198.\n' >&2
  exit 1
fi

printf 'Brand audit passed: no legacy product markers outside controlled exceptions.\n'
