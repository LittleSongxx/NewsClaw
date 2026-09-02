#!/usr/bin/env node
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
if (args.includes('--help') || args.length !== 2) {
  process.stdout.write('Usage: prepare-ai-news-candidate-shadow-review.js <daily-export-dir> <new-output-dir>\n');
  process.exit(args.includes('--help') ? 0 : 2);
}

const inputDir = path.resolve(args[0]);
const outputDir = path.resolve(args[1]);
const MARGINAL_PER_PROVIDER = 5;
const UNSELECTED_SAMPLE = 10;
const FORBIDDEN_REVIEW_KEYS = new Set([
  'candidateId', 'providerId', 'selectionStatus', 'selectionScore',
  'selectionReason', 'queryLane', 'storyId', 'timeConfidence', 'sourceClass',
  'scanRunId', 'marginalOnly', 'stratum'
]);

function fail(message) {
  process.stderr.write(`AI_NEWS_CANDIDATE_REVIEW_INVALID ${message}\n`);
  process.exit(1);
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    fail(`${path.basename(file)} is missing or invalid: ${error.message}`);
  }
}

function sha256(value) {
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(String(value));
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

function fileHash(file) {
  return sha256(fs.readFileSync(file));
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function records(document, file) {
  const value = document?.data?.records;
  if (!Array.isArray(value)) fail(`${path.basename(file)} has no data.records array`);
  return value;
}

function uniqueById(values, label) {
  const output = new Map();
  for (const value of values) {
    const id = String(value?.id || '');
    if (!id) fail(`${label} contains a candidate without id`);
    output.set(id, value);
  }
  return output;
}

function propertyNames(value, names = new Set()) {
  if (Array.isArray(value)) {
    for (const item of value) propertyNames(item, names);
  } else if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      names.add(key);
      propertyNames(child, names);
    }
  }
  return names;
}

function stableSample(values, count, seed) {
  return [...values]
    .sort((left, right) => sha256(`${seed}:${left.id}`)
      .localeCompare(sha256(`${seed}:${right.id}`)))
    .slice(0, count);
}

if (!fs.statSync(inputDir, {throwIfNoEntry: false})?.isDirectory()) {
  fail(`input directory not found: ${inputDir}`);
}
if (fs.existsSync(outputDir)) fail(`refusing to overwrite output directory: ${outputDir}`);

const scanFile = path.join(inputDir, 'scan-summary.json');
const discoveryFile = path.join(inputDir, 'discovery-run.json');
const scan = readJson(scanFile);
const discovery = readJson(discoveryFile);
const run = scan?.data?.run;
const snapshot = discovery?.data?.snapshot;
if (!run || run.runStatus !== 'COMPLETED') fail('scan run must be COMPLETED');
if (!snapshot?.snapshotHash || !snapshot?.rankingPolicyVersion) {
  fail('discovery snapshot identity is incomplete');
}

const candidateFiles = fs.readdirSync(inputDir)
  .filter(name => /^candidates-page-\d+\.json$/.test(name))
  .sort((a, b) => a.localeCompare(b, undefined, {numeric: true}));
if (candidateFiles.length === 0) fail('candidate page exports are missing');
const allCandidates = uniqueById(candidateFiles.flatMap(name => {
  const file = path.join(inputDir, name);
  return records(readJson(file), file);
}), 'candidate exports');
if (allCandidates.size !== Number(run.uniqueCandidateCount)) {
  fail(`candidate export count ${allCandidates.size} != run count ${run.uniqueCandidateCount}`);
}

const marginalFiles = fs.readdirSync(inputDir)
  .filter(name => /^marginal-.+-page-\d+\.json$/.test(name))
  .sort((a, b) => a.localeCompare(b, undefined, {numeric: true}));
if (marginalFiles.length === 0) fail('marginal-only page exports are missing');
const marginalByProvider = new Map();
for (const name of marginalFiles) {
  const match = name.match(/^marginal-(.+)-page-\d+\.json$/);
  const providerId = match[1];
  const file = path.join(inputDir, name);
  const providerRows = marginalByProvider.get(providerId) || [];
  providerRows.push(...records(readJson(file), file));
  marginalByProvider.set(providerId, providerRows);
}

const chosen = [];
const chosenIds = new Set();
const selected = [...allCandidates.values()].filter(value => value.selectionStatus === 'SELECTED');
if (selected.length !== Number(run.selectedCandidateCount)) {
  fail(`selected export count ${selected.length} != run count ${run.selectedCandidateCount}`);
}
for (const candidate of selected) {
  chosen.push({candidate, stratum: 'selected-census'});
  chosenIds.add(String(candidate.id));
}

const providerSampleCounts = {};
for (const providerId of [...marginalByProvider.keys()].sort()) {
  const candidates = [...uniqueById(marginalByProvider.get(providerId), providerId).values()]
    .filter(value => value.selectionStatus !== 'SELECTED' && !chosenIds.has(String(value.id)));
  const sample = stableSample(candidates, MARGINAL_PER_PROVIDER,
    `${snapshot.snapshotHash}:marginal:${providerId}`);
  providerSampleCounts[providerId] = sample.length;
  for (const candidate of sample) {
    chosen.push({candidate, stratum: `marginal-only:${providerId}`});
    chosenIds.add(String(candidate.id));
  }
}

const remaining = [...allCandidates.values()].filter(value =>
  value.selectionStatus !== 'SELECTED' && !chosenIds.has(String(value.id)));
const unselected = stableSample(remaining, UNSELECTED_SAMPLE,
  `${snapshot.snapshotHash}:unselected`);
for (const candidate of unselected) {
  chosen.push({candidate, stratum: 'unselected-sample'});
  chosenIds.add(String(candidate.id));
}

const cases = chosen.map(({candidate, stratum}) => ({
  caseId: `case-${sha256(`${snapshot.snapshotHash}:${candidate.id}`).slice(0, 20)}`,
  candidate,
  stratum
})).sort((left, right) =>
  sha256(`${snapshot.snapshotHash}:review-order:${left.caseId}`)
    .localeCompare(sha256(`${snapshot.snapshotHash}:review-order:${right.caseId}`)));

const worksheet = {
  schemaVersion: 'ai-news-candidate-shadow-review-v1',
  worksheetStatus: 'DRAFT',
  evaluationEligible: false,
  purpose: 'single-reviewer operational shadow triage; not an independent recall or gold-label claim',
  window: {
    start: snapshot.windowStart,
    end: snapshot.windowEnd,
    semantics: 'half-open'
  },
  reviewer: {
    reviewerId: '',
    startedAt: null,
    completedAt: null,
    attestsNoCoordinatorMappingExposure: false,
    attestsHumanReview: false
  },
  allowedValues: {
    relevance: ['relevant', 'irrelevant', 'unknown'],
    freshness: ['inside_window', 'outside_window', 'unknown'],
    decision: ['accept', 'reject', 'needs_capture'],
    issueCodes: ['stale', 'evergreen', 'marketing', 'tutorial', 'duplicate',
      'secondary-repost', 'out-of-scope', 'insufficient-evidence', 'other']
  },
  cases: cases.map(({caseId, candidate}) => ({
    caseId,
    material: {
      title: candidate.title || '',
      snippet: candidate.snippet || '',
      canonicalUrl: candidate.canonicalUrl || candidate.originalUrl || '',
      publishedAtHint: candidate.publishedAtHint || null
    },
    annotation: {
      relevance: null,
      freshness: null,
      duplicateGroup: '',
      decision: null,
      issueCodes: [],
      reason: '',
      notes: ''
    }
  }))
};

const leakedKeys = [...propertyNames(worksheet)].filter(key => FORBIDDEN_REVIEW_KEYS.has(key));
if (leakedKeys.length) fail(`review worksheet leaked coordinator keys: ${leakedKeys.join(',')}`);
const forbiddenValues = new Set([
  String(run.id), String(run.discoveryRunId),
  ...(scan.data.providers || []).map(value => String(value.providerId))
]);
const worksheetText = JSON.stringify(worksheet);
for (const value of forbiddenValues) {
  if (value && worksheetText.includes(value)) fail(`review worksheet leaked coordinator value: ${value}`);
}

const mapping = {
  schemaVersion: 'ai-news-candidate-shadow-review-mapping-v1',
  scanRunId: String(run.id),
  discoveryRunId: String(run.discoveryRunId),
  snapshotHash: snapshot.snapshotHash,
  cases: cases.map(({caseId, candidate, stratum}) => ({
    caseId,
    candidateId: String(candidate.id),
    providerId: candidate.providerId,
    stratum,
    selectionStatus: candidate.selectionStatus,
    selectionScore: candidate.selectionScore,
    selectionReason: candidate.selectionReason,
    queryLane: candidate.queryLane,
    storyId: candidate.storyId
  }))
};

const referenceTemplate = {
  schemaVersion: 'ai-news-reference-events-v1',
  status: 'DRAFT_EMPTY',
  window: worksheet.window,
  independenceRequirement: 'Populate from direct sources and human patrol independent of this worksheet.',
  collector: '',
  frozenAt: null,
  events: []
};

const reviewerFile = path.join(outputDir, 'reviewer', 'annotations.json');
const mappingFile = path.join(outputDir, 'coordinator', 'mapping.json');
const referenceFile = path.join(outputDir, 'coordinator', 'reference-events-template.json');
writeJson(reviewerFile, worksheet);
writeJson(mappingFile, mapping);
writeJson(referenceFile, referenceTemplate);
writeJson(path.join(outputDir, 'manifest.json'), {
  schemaVersion: 'ai-news-candidate-shadow-review-package-v1',
  status: 'AWAITING_INDEPENDENT_HUMAN_REVIEW',
  evaluationEligible: false,
  source: {
    scanRunId: String(run.id),
    discoveryRunId: String(run.discoveryRunId),
    rankingPolicyVersion: snapshot.rankingPolicyVersion,
    snapshotHash: snapshot.snapshotHash,
    rankingHash: snapshot.rankingHash,
    files: {
      'scan-summary.json': fileHash(scanFile),
      'discovery-run.json': fileHash(discoveryFile),
      ...Object.fromEntries(candidateFiles.map(name => [name, fileHash(path.join(inputDir, name))])),
      ...Object.fromEntries(marginalFiles.map(name => [name, fileHash(path.join(inputDir, name))]))
    }
  },
  sampling: {
    selectedCensus: selected.length,
    marginalPerProvider: MARGINAL_PER_PROVIDER,
    marginalSamples: providerSampleCounts,
    unselectedSample: unselected.length,
    totalReviewCases: cases.length,
    deterministicSeed: snapshot.snapshotHash
  },
  blindLeakageAudit: {
    passed: true,
    hidden: [...FORBIDDEN_REVIEW_KEYS].sort()
  },
  outputs: {
    'reviewer/annotations.json': fileHash(reviewerFile),
    'coordinator/mapping.json': fileHash(mappingFile),
    'coordinator/reference-events-template.json': fileHash(referenceFile)
  },
  limitations: [
    'Labels remain empty until an independent human completes and signs the worksheet.',
    'The empty reference-event template means recall is not yet measurable.',
    'Capture is disabled, so needs_capture is a triage outcome rather than a fetch failure.'
  ]
});

process.stdout.write(`AI_NEWS_CANDIDATE_REVIEW_PACKAGE=${JSON.stringify({
  output: outputDir,
  cases: cases.length,
  selected: selected.length,
  marginalSamples: providerSampleCounts,
  unselected: unselected.length,
  leakageAuditPassed: true
})}\n`);
