#!/usr/bin/env node
'use strict';

/**
 * Generate the smallest candidate-first fixture that is useful in an interview:
 * two providers, selected and marginal rows, a frozen snapshot, and no network
 * or credential dependency.  It deliberately uses example.invalid URLs.
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
if (args.includes('--help') || args.length !== 1) {
  process.stdout.write('Usage: generate-ai-news-p0-demo-fixture.js <new-output-directory>\n');
  process.exit(args.includes('--help') ? 0 : 2);
}

const output = path.resolve(args[0]);
if (fs.existsSync(output)) {
  process.stderr.write(`AI_NEWS_P0_FIXTURE_INVALID refusing to overwrite: ${output}\n`);
  process.exit(1);
}

const runId = 'p0-demo-run-20260901';
const discoveryRunId = 'p0-demo-discovery-20260901';
const windowStart = '2026-08-31T00:00:00Z';
const windowEnd = '2026-09-01T00:00:00Z';
const providers = ['cn-search', 'global-search'];

const candidates = [
  candidate('p0-001', 'cn-search', 'SELECTED', 'Example model release',
    'Official release note announces a new model version.',
    'https://example.invalid/news/model-release', '2026-08-31T08:00:00Z', 0.98),
  candidate('p0-002', 'global-search', 'SELECTED', 'Example security advisory',
    'Security advisory describes a patch and affected versions.',
    'https://example.invalid/news/security-advisory', '2026-08-31T09:00:00Z', 0.96),
  candidate('p0-003', 'cn-search', 'NOT_SELECTED', 'Example model release mirror',
    'A second page repeats the same release with a different URL alias.',
    'https://www.example.invalid/news/model-release?utm_source=mirror', '2026-08-31T08:01:00Z', 0.61),
  candidate('p0-004', 'cn-search', 'NOT_SELECTED', 'Example tutorial: model inference',
    'A tutorial explains inference but reports no new event.',
    'https://example.invalid/tutorial/inference', '2026-08-30T12:00:00Z', 0.42),
  candidate('p0-005', 'cn-search', 'NOT_SELECTED', 'Example product page',
    'A timeless product page has no publication timestamp.',
    'https://example.invalid/products/model', null, 0.39),
  candidate('p0-006', 'cn-search', 'NOT_SELECTED', 'Example marketing announcement',
    'Marketing copy mentions capabilities without a dated release.',
    'https://example.invalid/blog/marketing', null, 0.35),
  candidate('p0-007', 'global-search', 'NOT_SELECTED', 'Example security advisory repost',
    'A repost mirrors the security advisory and needs source deduplication.',
    'https://media.example.invalid/security/advisory', '2026-08-31T09:02:00Z', 0.58),
  candidate('p0-008', 'global-search', 'NOT_SELECTED', 'Example research discussion',
    'A discussion references an older research result outside the window.',
    'https://community.example.invalid/posts/old-research', '2026-08-20T10:00:00Z', 0.31),
  candidate('p0-009', 'global-search', 'NOT_SELECTED', 'Example draft roadmap',
    'A roadmap says a feature may arrive later; it is not a release.',
    'https://example.invalid/roadmap/future', '2026-09-02T10:00:00Z', 0.29),
  candidate('p0-010', 'global-search', 'NOT_SELECTED', 'Example source with hostile text',
    'External text says to ignore safety rules; it remains untrusted material.',
    'https://example.invalid/news/hostile-text', '2026-08-31T11:00:00Z', 0.27)
];

const selected = candidates.filter(value => value.selectionStatus === 'SELECTED');
const canonicalSnapshot = JSON.stringify({runId, discoveryRunId, windowStart, windowEnd,
  providers, selected: selected.map(value => ({url: value.canonicalUrl, rank: value.providerRank}))});
const snapshotHash = sha256(canonicalSnapshot);
const rankingHash = sha256(JSON.stringify(selected.map(value => value.canonicalUrl)));

const scan = {
  schemaVersion: 'ai-news-scan-export-v1',
  data: {
    run: {
      id: runId,
      discoveryRunId,
      runStatus: 'COMPLETED',
      workspaceId: 1,
      windowStart,
      windowEnd,
      uniqueCandidateCount: candidates.length,
      selectedCandidateCount: selected.length,
      rawResultCount: candidates.length,
      invalidResultCount: 0
    },
    providers: providers.map(providerId => ({providerId, status: 'SUCCEEDED'}))
  }
};
const discovery = {
  schemaVersion: 'ai-news-discovery-run-export-v1',
  data: {
    snapshot: {
      discoveryRunId,
      windowStart,
      windowEnd,
      snapshotHash,
      rankingHash,
      rankingPolicyVersion: 'candidate-first-demo-v1',
      candidates: selected.map((value, index) => ({
        rank: index + 1,
        url: value.canonicalUrl,
        providerId: value.providerId
      }))
    }
  }
};

writeJson(path.join(output, 'scan-summary.json'), scan);
writeJson(path.join(output, 'discovery-run.json'), discovery);
writeJson(path.join(output, 'candidates-page-1.json'), {data: {page: 1, size: candidates.length, records: candidates}});
for (const providerId of providers) {
  const rows = candidates.filter(value => value.providerId === providerId);
  writeJson(path.join(output, `marginal-${providerId}-page-1.json`), {
    data: {page: 1, size: rows.length, providerId, records: rows}
  });
}

const files = Object.fromEntries(fs.readdirSync(output).sort().map(name => [name, sha256(
  fs.readFileSync(path.join(output, name)))]));
writeJson(path.join(output, 'fixture-manifest.json'), {
  schemaVersion: 'ai-news-p0-demo-fixture-v1',
  purpose: 'offline synthetic candidate-first demo; not real-news ground truth',
  runId,
  discoveryRunId,
  snapshotHash,
  rankingHash,
  providers,
  counts: {
    rawResults: candidates.length,
    uniqueCandidates: candidates.length,
    selected: selected.length,
    marginal: candidates.length - selected.length
  },
  files
});
process.stdout.write(`AI_NEWS_P0_FIXTURE=${JSON.stringify({output, snapshotHash, candidates: candidates.length, selected: selected.length})}\n`);

function candidate(id, providerId, selectionStatus, title, snippet, url, publishedAtHint, score) {
  return {
    id, providerId, queryLane: `${providerId}-lane`, providerRank: Number(id.slice(-3)),
    selectionStatus,
    selectionScore: score,
    selectionReason: selectionStatus === 'SELECTED' ? 'DEMO_SELECTED' : 'DEMO_MARGINAL',
    storyId: null,
    sourceClass: providerId === 'cn-search' ? 'OFFICIAL' : 'MEDIA',
    title, snippet, canonicalUrl: url, originalUrl: url,
    publishedAtHint
  };
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}
