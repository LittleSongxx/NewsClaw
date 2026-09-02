#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s --allow-mutation <capture-manifest.json> <output-report.json>\n' "$0" >&2
}

# This evaluator deliberately exercises the persistence boundary: source
# captures are INSERTed and the synthetic quote probe is POSTed as an event.
# A missing flag must therefore fail before login/network access so a copied
# command cannot silently write to a live workspace.
allow_mutation=false
if [[ "${1:-}" == "--allow-mutation" ]]; then
  allow_mutation=true
  shift
fi
if [[ $# -ne 2 ]]; then
  usage
  exit 2
fi
if [[ "$allow_mutation" != true ]]; then
  printf '%s\n' 'Refusing to run: this funnel writes source captures and synthetic events.' >&2
  printf '%s\n' 'Use --allow-mutation only with NEWSCLAW_EVAL_ISOLATED=1 and a disposable loopback server.' >&2
  exit 2
fi
if [[ "${NEWSCLAW_EVAL_ISOLATED:-}" != "1" ]]; then
  printf '%s\n' 'Refusing mutation: set NEWSCLAW_EVAL_ISOLATED=1 after confirming the target database is disposable.' >&2
  exit 2
fi

manifest=$1
output=$2
base_url=${NEWSCLAW_EVAL_BASE_URL:-http://127.0.0.1:18088}
username=${NEWSCLAW_EVAL_USERNAME:-}
password=${NEWSCLAW_EVAL_PASSWORD:-}
workspace_id=${NEWSCLAW_EVAL_WORKSPACE_ID:-1}

for command_name in curl jq sha256sum base64; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing required command: $command_name" >&2
    exit 2
  }
done

[[ -f "$manifest" ]] || { echo "Manifest not found: $manifest" >&2; exit 2; }
[[ -n "$username" ]] || { echo "NEWSCLAW_EVAL_USERNAME is required" >&2; exit 2; }
[[ -n "$password" ]] || { echo "NEWSCLAW_EVAL_PASSWORD is required" >&2; exit 2; }
[[ "$workspace_id" =~ ^[1-9][0-9]*$ ]] || {
  echo "NEWSCLAW_EVAL_WORKSPACE_ID must be a positive integer" >&2
  exit 2
}
[[ "$base_url" =~ ^https?://[^[:space:]]+$ ]] || {
  echo "NEWSCLAW_EVAL_BASE_URL must be an absolute HTTP(S) URL" >&2
  exit 2
}
[[ "$base_url" =~ ^https?://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]+)?(/.*)?$ ]] || {
  echo "Refusing mutation: NEWSCLAW_EVAL_BASE_URL must target a loopback host" >&2
  exit 2
}
[[ -n "${NEWSCLAW_EVAL_WORKSPACE_ID+x}" ]] || {
  echo "NEWSCLAW_EVAL_WORKSPACE_ID must be set explicitly for a mutating evaluation" >&2
  exit 2
}

jq -e '
  .schemaVersion == "1.0"
  and (.windowStart | type == "string")
  and (.windowEnd | type == "string")
  and (.candidates | type == "array" and length > 0)
  and ((.candidates | map(.url) | unique | length) == (.candidates | length))
' "$manifest" >/dev/null || {
  echo "Manifest contract validation failed" >&2
  exit 2
}

temp_dir=$(mktemp -d)
trap 'rm -rf -- "$temp_dir"' EXIT
chmod 700 "$temp_dir"
results_jsonl="$temp_dir/results.jsonl"
login_body="$temp_dir/login.json"
login_response="$temp_dir/login-response.json"
auth_config="$temp_dir/auth.curl"

jq -n --arg username "$username" --arg password "$password" \
  '{username:$username,password:$password}' > "$login_body"
chmod 600 "$login_body"
curl --noproxy '*' --silent --show-error --fail-with-body --max-time 15 \
  -H 'Content-Type: application/json' --data-binary "@$login_body" \
  "$base_url/api/v1/auth/login" > "$login_response"
token=$(jq -er '.data.token // .data.accessToken' "$login_response")
printf 'header = "Authorization: Bearer %s"\nheader = "X-Workspace-Id: %s"\n' \
  "$token" "$workspace_id" > "$auth_config"
chmod 600 "$auth_config"
unset token password

window_start=$(jq -r '.windowStart' "$manifest")
window_end=$(jq -r '.windowEnd' "$manifest")
manifest_hash=$(sha256sum "$manifest" | awk '{print $1}')
total=$(jq '.candidates | length' "$manifest")
index=0

while IFS= read -r encoded_candidate; do
  index=$((index + 1))
  candidate=$(printf '%s' "$encoded_candidate" | base64 --decode)
  sample_id=$(jq -r '.sampleId' <<<"$candidate")
  source_url=$(jq -r '.url' <<<"$candidate")
  title=$(jq -r '.title' <<<"$candidate")
  stratum=$(jq -r '.stratum' <<<"$candidate")
  capture_response="$temp_dir/capture-$index.json"
  capture_payload="$temp_dir/capture-$index-request.json"
  jq -n --arg sourceUrl "$source_url" '{sourceUrl:$sourceUrl}' > "$capture_payload"

  capture_started=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  curl_exit=0
  capture_metrics=$(curl --noproxy '*' --silent --show-error --max-time 75 \
    --config "$auth_config" -H 'Content-Type: application/json' \
    --data-binary "@$capture_payload" -o "$capture_response" \
    --write-out '%{http_code}\t%{time_total}' \
    "$base_url/api/v1/ai-news/source-captures") || curl_exit=$?
  capture_finished=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  capture_http=$(cut -f1 <<<"${capture_metrics:-0}")
  capture_seconds=$(cut -f2 <<<"${capture_metrics:-0\t0}")
  [[ -s "$capture_response" ]] || printf '{"code":0,"msg":"empty HTTP response"}' > "$capture_response"
  capture_success=false
  if [[ "$curl_exit" -eq 0 && "$capture_http" =~ ^2 && \
        "$(jq -r '.code // 0' "$capture_response")" == "200" && \
        "$(jq -r '.data.captureId // ""' "$capture_response")" != "" ]]; then
    capture_success=true
  fi

  read_attempted=false
  read_success=false
  read_http=0
  read_seconds=0
  read_code=0
  read_message="not attempted"
  read_chars=0
  read_truncated=false
  quote_probe_hash=null
  binding_attempted=false
  binding_success=false
  binding_http=0
  binding_seconds=0
  binding_code=0
  binding_message="not attempted"
  event_id=null
  evidence_count=0
  capture_bound_count=0

  if [[ "$capture_success" == true ]]; then
    capture_id=$(jq -r '.data.captureId' "$capture_response")
    read_attempted=true
    read_response="$temp_dir/read-$index.json"
    read_exit=0
    read_metrics=$(curl --noproxy '*' --silent --show-error --max-time 20 \
      --config "$auth_config" -o "$read_response" \
      --write-out '%{http_code}\t%{time_total}' \
      "$base_url/api/v1/ai-news/source-captures/$capture_id?startOffset=0") || read_exit=$?
    read_http=$(cut -f1 <<<"${read_metrics:-0}")
    read_seconds=$(cut -f2 <<<"${read_metrics:-0\t0}")
    [[ -s "$read_response" ]] || printf '{"code":0,"msg":"empty HTTP response"}' > "$read_response"
    read_code=$(jq -r '.code // 0' "$read_response")
    read_message=$(jq -r '.msg // ""' "$read_response")
    read_chars=$(jq -r '(.data.content // "") | length' "$read_response")
    read_truncated=$(jq -r '.data.truncated // false' "$read_response")
    if [[ "$read_exit" -eq 0 && "$read_http" =~ ^2 && "$read_code" == "200" && "$read_chars" -ge 12 ]]; then
      read_success=true
      quote_probe=$(jq -r '(.data.content // "") | gsub("\\s+";" ") | .[0:200]' "$read_response")
      quote_probe_hash=$(printf '%s' "$quote_probe" | sha256sum | awk '{print $1}')
      binding_attempted=true
      upsert_payload="$temp_dir/upsert-$index.json"
      upsert_response="$temp_dir/upsert-$index-response.json"
      jq -n \
        --arg eventKey "capture-funnel-$sample_id" \
        --arg title "$title" \
        --arg claim "$quote_probe" \
        --arg captureId "$capture_id" \
        '{eventKey:$eventKey,title:$title,summary:$title,category:"industry",entities:[],claims:[$claim],conflicts:[],evidence:[{sourceUrl:null,sourceTitle:null,sourcePublishedAt:null,sourceTier:null,claim:$claim,quote:$claim,confidence:1.0,semanticRelation:"entails",relationConfidence:1.0,captureId:$captureId}]}' \
        > "$upsert_payload"
      binding_exit=0
      binding_metrics=$(curl --noproxy '*' --silent --show-error --max-time 30 \
        --config "$auth_config" -H 'Content-Type: application/json' \
        --data-binary "@$upsert_payload" -o "$upsert_response" \
        --write-out '%{http_code}\t%{time_total}' \
        "$base_url/api/v1/ai-news/events") || binding_exit=$?
      binding_http=$(cut -f1 <<<"${binding_metrics:-0}")
      binding_seconds=$(cut -f2 <<<"${binding_metrics:-0\t0}")
      [[ -s "$upsert_response" ]] || printf '{"code":0,"msg":"empty HTTP response"}' > "$upsert_response"
      binding_code=$(jq -r '.code // 0' "$upsert_response")
      binding_message=$(jq -r '.msg // ""' "$upsert_response")
      if [[ "$binding_exit" -eq 0 && "$binding_http" =~ ^2 && "$binding_code" == "200" ]]; then
        binding_success=true
        event_id=$(jq -r '.data.id // empty' "$upsert_response")
        if [[ -n "$event_id" ]]; then
          detail_response="$temp_dir/event-$index.json"
          detail_exit=0
          curl --noproxy '*' --silent --show-error --max-time 20 \
            --config "$auth_config" -o "$detail_response" \
            "$base_url/api/v1/ai-news/events/$event_id" || detail_exit=$?
          if [[ "$detail_exit" -eq 0 && -s "$detail_response" ]]; then
            evidence_count=$(jq -r '(.data.evidence // []) | length' "$detail_response")
            # Request DTO uses captureId, while the persisted evidence entity
            # intentionally exposes the server-owned column as sourceCaptureId.
            capture_bound_count=$(jq -r \
              '[.data.evidence[]? | select((.sourceCaptureId // .captureId) != null)] | length' \
              "$detail_response")
          fi
        fi
      fi
      unset quote_probe
    fi
  fi

  jq -n \
    --argjson candidate "$candidate" \
    --arg captureStartedAt "$capture_started" \
    --arg captureFinishedAt "$capture_finished" \
    --argjson curlExit "$curl_exit" \
    --argjson captureHttp "${capture_http:-0}" \
    --argjson captureSeconds "${capture_seconds:-0}" \
    --argjson captureSuccess "$capture_success" \
    --slurpfile captureResponse "$capture_response" \
    --argjson readAttempted "$read_attempted" \
    --argjson readSuccess "$read_success" \
    --argjson readHttp "$read_http" \
    --argjson readSeconds "$read_seconds" \
    --argjson readCode "$read_code" \
    --arg readMessage "$read_message" \
    --argjson readChars "$read_chars" \
    --argjson readTruncated "$read_truncated" \
    --arg quoteProbeHash "$quote_probe_hash" \
    --argjson bindingAttempted "$binding_attempted" \
    --argjson bindingSuccess "$binding_success" \
    --argjson bindingHttp "$binding_http" \
    --argjson bindingSeconds "$binding_seconds" \
    --argjson bindingCode "$binding_code" \
    --arg bindingMessage "$binding_message" \
    --arg eventId "$event_id" \
    --argjson evidenceCount "$evidence_count" \
    --argjson captureBoundCount "$capture_bound_count" \
    '
      $captureResponse[0] as $response |
      {
        candidate:$candidate,
        capture:{
          startedAt:$captureStartedAt,finishedAt:$captureFinishedAt,curlExit:$curlExit,
          httpStatus:$captureHttp,durationSeconds:$captureSeconds,
          apiCode:($response.code // 0),message:($response.msg // ""),success:$captureSuccess,
          snapshot:(if $captureSuccess then {
            captureId:$response.data.captureId,finalUrl:$response.data.finalUrl,
            sourceTitle:$response.data.sourceTitle,
            sourcePublishedAt:($response.data.sourcePublishedAtUtc // $response.data.sourcePublishedAt),
            publishedAtMethod:$response.data.publishedAtMethod,
            sourceTimeOrigin:$response.data.sourceTimeOrigin,
            sourceTimeAttestationStatus:$response.data.sourceTimeAttestationStatus,
            sourceTimeItemVersionId:$response.data.sourceTimeItemVersionId,
            sourceTimeAttestationHash:$response.data.sourceTimeAttestationHash,
            sourceTier:$response.data.sourceTier,
            captureMethod:$response.data.captureMethod,
            upstreamHttpStatus:$response.data.httpStatus,fetchedAt:$response.data.fetchedAt,
            contentHash:$response.data.contentHash,extractedTextHash:$response.data.extractedTextHash,
            extractorName:$response.data.extractorName,extractorVersion:$response.data.extractorVersion,
            extractorConfigHash:$response.data.extractorConfigHash,
            extractionFallback:$response.data.extractionFallback,
            extractionWarning:$response.data.extractionWarning,textLength:$response.data.textLength,
            truncated:$response.data.truncated
          } else null end)
        },
        read:{attempted:$readAttempted,success:$readSuccess,httpStatus:$readHttp,
          durationSeconds:$readSeconds,apiCode:$readCode,message:$readMessage,
          returnedChars:$readChars,truncated:$readTruncated,
          quoteProbeHash:(if $quoteProbeHash=="null" then null else $quoteProbeHash end)},
        evidenceBinding:{attempted:$bindingAttempted,success:$bindingSuccess,httpStatus:$bindingHttp,
          durationSeconds:$bindingSeconds,apiCode:$bindingCode,message:$bindingMessage,
          eventId:(if $eventId=="null" or $eventId=="" then null else $eventId end),
          evidenceCount:$evidenceCount,captureBoundEvidenceCount:$captureBoundCount}
      }
    ' >> "$results_jsonl"

  printf '[%d/%d] %s stratum=%s capture=%s read=%s bind=%s\n' \
    "$index" "$total" "$sample_id" "$stratum" "$capture_success" "$read_success" "$binding_success"
done < <(jq -r '.candidates[] | @base64' "$manifest")

jq -s \
  --arg generatedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  --arg manifest "$manifest" \
  --arg manifestHash "$manifest_hash" \
  --arg baseUrl "$base_url" \
  --argjson workspaceId "$workspace_id" \
  --arg windowStart "$window_start" \
  --arg windowEnd "$window_end" \
  '
    def in_window($value): $value != null and $value >= $windowStart and $value < $windowEnd;
    def failure_category:
      if .capture.success then "success"
      elif .capture.curlExit != 0 then "transport_error"
      elif (.capture.message | test("超时|timeout";"i")) then "timeout"
      elif (.capture.message | test("抽取服务返回 HTTP 422|extract.*HTTP 422";"i")) then "extractor_http_422"
      elif (.capture.message | test("handshake|TLS|SSL";"i")) then "tls_error"
      elif (.capture.message | test("HTTP 401";"i")) then "upstream_http_401"
      elif (.capture.message | test("HTTP 403";"i")) then "upstream_http_403"
      elif (.capture.message | test("HTTP 404";"i")) then "upstream_http_404"
      elif (.capture.message | test("HTTP 422";"i")) then "upstream_http_422"
      elif (.capture.message | test("HTTP 429";"i")) then "upstream_http_429"
      elif (.capture.message | test("HTTP [45][0-9][0-9]";"i")) then "upstream_http_other"
      elif (.capture.message | test("超过部署上限|too large";"i")) then "body_too_large"
      elif (.capture.message | test("正文过短|insufficient";"i")) then "insufficient_content"
      elif (.capture.message | test("抽取|extract";"i")) then "extraction_error"
      elif (.capture.message | test("正文|content";"i")) then "empty_content"
      elif (.capture.message | test("安全|URL";"i")) then "url_rejected"
      else "other_error" end;
    map(. + {
      derived:{
        failureCategory:(failure_category),
        exactCaptureBinding:(.evidenceBinding.success
          and .evidenceBinding.captureBoundEvidenceCount > 0),
        explicitSourceTime:(.capture.snapshot.sourcePublishedAt != null),
        sourceTimeInWindow:(in_window(.capture.snapshot.sourcePublishedAt)),
        evidenceReady:(.capture.success and .read.success
          and .evidenceBinding.success and .evidenceBinding.captureBoundEvidenceCount > 0
          and in_window(.capture.snapshot.sourcePublishedAt)),
        hintConfirmed:(.candidate.temporalStatus == "IN_WINDOW"
          and in_window(.capture.snapshot.sourcePublishedAt)),
        unknownResolvedCurrent:(.candidate.temporalStatus == "UNKNOWN"
          and in_window(.capture.snapshot.sourcePublishedAt)),
        unknownResolvedOutside:(.candidate.temporalStatus == "UNKNOWN"
          and .capture.snapshot.sourcePublishedAt != null
          and (in_window(.capture.snapshot.sourcePublishedAt) | not))
      }
    }) as $results |
    {
      schemaVersion:"1.0",generatedAt:$generatedAt,
      manifest:{path:$manifest,sha256:$manifestHash},
      execution:{baseUrl:$baseUrl,workspaceId:$workspaceId,windowStart:$windowStart,windowEnd:$windowEnd,
        mutationMode:"explicit-isolated-loopback",isolationAttested:true,
        syntheticEvidenceBinding:true,
        selectionPolicy:"all unique URLs in the frozen manifest; sequential; no success preselection",
        quoteProbePolicy:"first 200 normalized characters returned by read_capture; text omitted, SHA-256 retained",
        publicationGate:"source timestamp must be explicit UTC from article page metadata or a governed publisher structured-source attestation, and inside the half-open frozen window"},
      summary:{
        attempted:($results|length),
        captureSuccess:([$results[]|select(.capture.success)]|length),
        readSuccess:([$results[]|select(.read.success)]|length),
        exactBindingSuccess:([$results[]|select(.derived.exactCaptureBinding)]|length),
        explicitSourceTime:([$results[]|select(.derived.explicitSourceTime)]|length),
        sourceTimeInWindow:([$results[]|select(.derived.sourceTimeInWindow)]|length),
        evidenceReady:([$results[]|select(.derived.evidenceReady)]|length),
        hintConfirmed:([$results[]|select(.derived.hintConfirmed)]|length),
        unknownResolvedCurrent:([$results[]|select(.derived.unknownResolvedCurrent)]|length),
        unknownResolvedOutside:([$results[]|select(.derived.unknownResolvedOutside)]|length),
        bySourceTimeOrigin:($results|group_by(.capture.snapshot.sourceTimeOrigin // "NONE")
          |map({origin:(.[0].capture.snapshot.sourceTimeOrigin // "NONE"),count:length})),
        bySourceTimeAttestationStatus:($results
          |group_by(.capture.snapshot.sourceTimeAttestationStatus // "NOT_REPORTED")
          |map({status:(.[0].capture.snapshot.sourceTimeAttestationStatus // "NOT_REPORTED"),count:length})),
        byFailure:($results|group_by(.derived.failureCategory)|map({category:.[0].derived.failureCategory,count:length})),
        byStratum:($results|group_by(.candidate.stratum)|map({
          stratum:.[0].candidate.stratum,attempted:length,
          captureSuccess:([.[]|select(.capture.success)]|length),
          explicitSourceTime:([.[]|select(.derived.explicitSourceTime)]|length),
          sourceTimeInWindow:([.[]|select(.derived.sourceTimeInWindow)]|length),
          evidenceReady:([.[]|select(.derived.evidenceReady)]|length)
        }))
      },
      results:$results
    }
  ' "$results_jsonl" > "$output"

jq '.summary' "$output"
