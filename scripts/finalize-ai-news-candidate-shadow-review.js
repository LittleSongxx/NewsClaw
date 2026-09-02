#!/usr/bin/env node
'use strict';

const childProcess = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const TOOL_VERSION = '1.0.0';
const CATEGORIES = [
  'model', 'product', 'open_source', 'research', 'security', 'infrastructure',
  'partnership', 'funding', 'robotics', 'industry', 'policy'
];
const LANGUAGES = ['zh', 'en', 'multilingual'];
const SOURCE_FAMILIES = ['official', 'media', 'community'];
const REQUIRED_SECONDARY_ADJUDICATION_IDS = new Set([
  'gold-20260829-002', 'gold-20260829-004', 'gold-20260829-005',
  'gold-20260829-006', 'gold-20260829-007', 'gold-20260829-008',
  'gold-20260829-009', 'gold-20260829-010', 'gold-20260829-013',
  'gold-20260829-016', 'gold-20260829-017', 'gold-20260829-018',
  'gold-20260829-020', 'gold-20260829-021', 'gold-20260829-022'
]);
const ISSUE_CODES = [
  'stale', 'evergreen', 'marketing', 'tutorial', 'duplicate',
  'secondary-repost', 'out-of-scope', 'insufficient-evidence', 'other'
];
const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);

if (args.includes('--help') || args.length !== 3) {
  process.stdout.write([
    'Usage: finalize-ai-news-candidate-shadow-review.js <day1-root> <submission-dir> <new-output-dir>',
    '',
    'submission-dir must contain:',
    '  annotations.json',
    '  reference-events.json',
    '  qc.json',
    '  candidate-adjudications.json',
    'See test-finalize-ai-news-candidate-shadow-review.js for the executable minimal contract.',
    ''
  ].join('\n'));
  process.exit(args.includes('--help') ? 0 : 2);
}

const dayRoot = path.resolve(args[0]);
const submissionDir = path.resolve(args[1]);
const outputDir = path.resolve(args[2]);
const exportDir = path.join(dayRoot, 'export');
const packageDir = path.join(dayRoot, 'review-package');

function fail(message) {
  process.stderr.write(`AI_NEWS_CANDIDATE_FINALIZATION_INVALID ${message}\n`);
  process.exit(1);
}

function check(condition, message) {
  if (!condition) fail(message);
}

function readBytes(file, label = path.basename(file)) {
  try {
    return fs.readFileSync(file);
  } catch (error) {
    fail(`${label} is missing or unreadable: ${error.message}`);
  }
}

function readJson(file, label = path.basename(file)) {
  try {
    return JSON.parse(readBytes(file, label).toString('utf8'));
  } catch (error) {
    fail(`${label} is invalid JSON: ${error.message}`);
  }
}

function sha256(value) {
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(String(value));
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

function fileHash(file) {
  return sha256(readBytes(file));
}

function jsonBytes(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key =>
      `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function same(left, right) {
  return canonical(left) === canonical(right);
}

function text(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function utc(value) {
  const match = typeof value === 'string' && value.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z$/);
  if (!match) return false;
  const parts = match.slice(1).map(Number);
  const instant = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2], parts[3], parts[4], parts[5]));
  return instant.getUTCFullYear() === parts[0]
    && instant.getUTCMonth() === parts[1] - 1
    && instant.getUTCDate() === parts[2]
    && instant.getUTCHours() === parts[3]
    && instant.getUTCMinutes() === parts[4]
    && instant.getUTCSeconds() === parts[5];
}

function httpUrl(value) {
  if (!text(value)) return false;
  try {
    return ['http:', 'https:'].includes(new URL(value).protocol);
  } catch (_error) {
    return false;
  }
}

function exactKeys(value, expected, label) {
  check(value && typeof value === 'object' && !Array.isArray(value), `${label} must be an object`);
  const actual = Object.keys(value).sort();
  check(same(actual, [...expected].sort()), `${label} fields must be exactly: ${expected.join(', ')}`);
}

function uniqueMap(values, key, label) {
  check(Array.isArray(values), `${label} must be an array`);
  const result = new Map();
  for (const value of values) {
    const id = String(value?.[key] || '');
    check(id, `${label} contains an item without ${key}`);
    check(!result.has(id), `${label} contains duplicate ${key}: ${id}`);
    result.set(id, value);
  }
  return result;
}

function stringSet(values, label) {
  check(Array.isArray(values) && values.every(text), `${label} must be a non-empty-string array`);
  check(new Set(values).size === values.length, `${label} must not contain duplicates`);
  return new Set(values);
}

function displayPath(file) {
  const relative = path.relative(root, file);
  return relative && !relative.startsWith(`..${path.sep}`) ? relative : file;
}

function git(args) {
  return childProcess.execFileSync('git', args, {cwd: root, encoding: 'utf8'}).trim();
}

check(!fs.existsSync(outputDir), `refusing to overwrite output directory: ${outputDir}`);
check(fs.statSync(dayRoot, {throwIfNoEntry: false})?.isDirectory(), `day1 root not found: ${dayRoot}`);
check(fs.statSync(submissionDir, {throwIfNoEntry: false})?.isDirectory(),
  `submission directory not found: ${submissionDir}`);

const manifestFile = path.join(packageDir, 'manifest.json');
const originalWorksheetFile = path.join(packageDir, 'reviewer', 'annotations.json');
const mappingFile = path.join(packageDir, 'coordinator', 'mapping.json');
const referenceTemplateFile = path.join(packageDir, 'coordinator', 'reference-events-template.json');
const annotationsFile = path.join(submissionDir, 'annotations.json');
const referenceEventsFile = path.join(submissionDir, 'reference-events.json');
const qcFile = path.join(submissionDir, 'qc.json');
const adjudicationsFile = path.join(submissionDir, 'candidate-adjudications.json');

const manifest = readJson(manifestFile, 'original manifest');
const originalWorksheet = readJson(originalWorksheetFile, 'original reviewer worksheet');
const mapping = readJson(mappingFile, 'coordinator mapping');
const referenceTemplate = readJson(referenceTemplateFile, 'reference template');
const annotations = readJson(annotationsFile, 'submitted annotations');
const referenceEvents = readJson(referenceEventsFile, 'submitted reference events');
const qc = readJson(qcFile, 'QC submission');
const adjudications = readJson(adjudicationsFile, 'candidate adjudications');

check(manifest.schemaVersion === 'ai-news-candidate-shadow-review-package-v1',
  'unsupported original package schemaVersion');
check(manifest.status === 'AWAITING_INDEPENDENT_HUMAN_REVIEW'
  && manifest.evaluationEligible === false, 'original package status is inconsistent');
for (const [name, expected] of Object.entries(manifest.source?.files || {})) {
  check(/^[a-f0-9]{64}$/.test(expected), `invalid source hash in original manifest: ${name}`);
  check(fileHash(path.join(exportDir, name)) === expected, `original export hash mismatch: ${name}`);
}
for (const [name, expected] of Object.entries(manifest.outputs || {})) {
  check(/^[a-f0-9]{64}$/.test(expected), `invalid output hash in original manifest: ${name}`);
  check(fileHash(path.join(packageDir, name)) === expected, `original package hash mismatch: ${name}`);
}

const scan = readJson(path.join(exportDir, 'scan-summary.json'), 'scan summary');
const discovery = readJson(path.join(exportDir, 'discovery-run.json'), 'discovery run');
const run = scan?.data?.run;
const snapshot = discovery?.data?.snapshot;
check(run?.runStatus === 'COMPLETED', 'scan run must be COMPLETED');
check(snapshot?.snapshotHash && snapshot?.rankingHash && snapshot?.rankingPolicyVersion,
  'discovery snapshot identity is incomplete');
check(String(run.id) === String(manifest.source.scanRunId)
  && String(run.discoveryRunId) === String(manifest.source.discoveryRunId),
  'scan identity differs from original manifest');
check(snapshot.snapshotHash === manifest.source.snapshotHash
  && snapshot.rankingHash === manifest.source.rankingHash
  && snapshot.rankingPolicyVersion === manifest.source.rankingPolicyVersion,
  'discovery identity differs from original manifest');
check(String(mapping.scanRunId) === String(run.id)
  && String(mapping.discoveryRunId) === String(run.discoveryRunId)
  && mapping.snapshotHash === snapshot.snapshotHash, 'coordinator mapping identity mismatch');

const candidateFiles = Object.keys(manifest.source.files)
  .filter(name => /^candidates-page-\d+\.json$/.test(name))
  .sort((left, right) => left.localeCompare(right, undefined, {numeric: true}));
check(candidateFiles.length > 0, 'candidate exports are missing from original manifest');
const candidates = candidateFiles.flatMap(name => {
  const records = readJson(path.join(exportDir, name), name)?.data?.records;
  check(Array.isArray(records), `${name} has no data.records array`);
  return records;
});
const candidateById = uniqueMap(candidates, 'id', 'candidate exports');
check(candidateById.size === Number(run.uniqueCandidateCount),
  `candidate count ${candidateById.size} != scan count ${run.uniqueCandidateCount}`);

const selectedSnapshot = snapshot.candidates;
check(Array.isArray(selectedSnapshot) && selectedSnapshot.length === Number(run.selectedCandidateCount),
  'snapshot selected candidates do not match scan count');
check(selectedSnapshot.length > 0, 'at least one snapshot-selected candidate is required');
const rankByCandidateId = new Map();
for (let index = 0; index < selectedSnapshot.length; index += 1) {
  const selected = selectedSnapshot[index];
  check(selected.rank === index + 1, 'snapshot selected ranks must be contiguous from 1');
  const matches = candidates.filter(candidate =>
    candidate.canonicalUrl === selected.url || candidate.originalUrl === selected.url);
  check(matches.length === 1, `snapshot rank ${selected.rank} URL must match exactly one exported candidate`);
  check(matches[0].selectionStatus === 'SELECTED',
    `snapshot rank ${selected.rank} does not map to a SELECTED candidate`);
  rankByCandidateId.set(String(matches[0].id), selected.rank);
}
const selectedIds = candidates.filter(candidate => candidate.selectionStatus === 'SELECTED')
  .map(candidate => String(candidate.id));
check(selectedIds.length === selectedSnapshot.length
  && selectedIds.every(id => rankByCandidateId.has(id)),
  'snapshot does not cover every SELECTED candidate');
// ponytail: unselected rows have no global rank; stable IDs only provide a contiguous scorer tail.
const tail = candidates.filter(candidate => candidate.selectionStatus !== 'SELECTED')
  .sort((left, right) => String(left.id).localeCompare(String(right.id), undefined, {numeric: true}));
tail.forEach((candidate, index) => rankByCandidateId.set(String(candidate.id), selectedSnapshot.length + index + 1));

for (const field of ['schemaVersion', 'worksheetStatus', 'evaluationEligible', 'purpose', 'window',
  'allowedValues']) {
  check(same(annotations[field], originalWorksheet[field]), `submitted annotations changed ${field}`);
}
check(annotations.worksheetStatus === 'DRAFT' && annotations.evaluationEligible === false,
  'legacy worksheet status/evaluationEligible must remain unchanged');
check(Array.isArray(originalWorksheet.cases) && Array.isArray(annotations.cases)
  && originalWorksheet.cases.length === annotations.cases.length, 'review case count changed');
for (let index = 0; index < originalWorksheet.cases.length; index += 1) {
  const issued = originalWorksheet.cases[index];
  const submitted = annotations.cases[index];
  check(issued.caseId === submitted?.caseId, `review case order/id changed at index ${index}`);
  check(same(issued.material, submitted.material), `review material changed: ${issued.caseId}`);
}

exactKeys(annotations.reviewer,
  ['reviewerId', 'startedAt', 'completedAt', 'attestsNoCoordinatorMappingExposure', 'attestsHumanReview'],
  'reviewer');
check(text(annotations.reviewer.reviewerId), 'reviewerId is required');
check(utc(annotations.reviewer.startedAt) && utc(annotations.reviewer.completedAt)
  && Date.parse(annotations.reviewer.startedAt) <= Date.parse(annotations.reviewer.completedAt),
  'reviewer timestamps must be ordered UTC instants');
check(annotations.reviewer.attestsNoCoordinatorMappingExposure === true
  && annotations.reviewer.attestsHumanReview === true, 'reviewer attestations must be true');
const allowed = annotations.allowedValues;
check(same(allowed?.issueCodes, ISSUE_CODES), 'review issue-code vocabulary drifted');
const duplicateGroups = new Map();
for (const item of annotations.cases) {
  exactKeys(item, ['caseId', 'material', 'annotation'], `review case ${item.caseId}`);
  const value = item.annotation;
  exactKeys(value, ['relevance', 'freshness', 'duplicateGroup', 'decision', 'issueCodes', 'reason', 'notes'],
    `annotation ${item.caseId}`);
  check(allowed.relevance.includes(value.relevance), `invalid relevance: ${item.caseId}`);
  check(allowed.freshness.includes(value.freshness), `invalid freshness: ${item.caseId}`);
  check(allowed.decision.includes(value.decision), `invalid decision: ${item.caseId}`);
  check(typeof value.duplicateGroup === 'string', `duplicateGroup must be a string: ${item.caseId}`);
  const issues = stringSet(value.issueCodes, `issueCodes ${item.caseId}`);
  check([...issues].every(issue => allowed.issueCodes.includes(issue)), `invalid issue code: ${item.caseId}`);
  check(text(value.reason) && typeof value.notes === 'string', `reason/notes incomplete: ${item.caseId}`);
  check((value.freshness === 'outside_window') === issues.has('stale'),
    `stale/freshness inconsistency: ${item.caseId}`);
  check(!issues.has('duplicate') || text(value.duplicateGroup),
    `duplicate issue lacks duplicateGroup: ${item.caseId}`);
  if (value.decision === 'accept') {
    check(value.relevance === 'relevant' && value.freshness === 'inside_window',
      `accepted case is not relevant and inside-window: ${item.caseId}`);
  }
  if (value.duplicateGroup) {
    const members = duplicateGroups.get(value.duplicateGroup) || [];
    members.push(value);
    duplicateGroups.set(value.duplicateGroup, members);
  }
}
for (const [group, members] of duplicateGroups) {
  check(members.length >= 2, `duplicate group has fewer than two members: ${group}`);
  check(members.filter(value => value.issueCodes.includes('duplicate')).length === members.length - 1,
    `duplicate group must have exactly one representative: ${group}`);
}

const originalCaseById = uniqueMap(originalWorksheet.cases, 'caseId', 'issued review cases');
const submittedCaseById = uniqueMap(annotations.cases, 'caseId', 'submitted review cases');
const mappingByCaseId = uniqueMap(mapping.cases, 'caseId', 'coordinator mapping');
check(mappingByCaseId.size === submittedCaseById.size, 'review/mapping case count mismatch');
for (const [caseId, mapped] of mappingByCaseId) {
  check(originalCaseById.has(caseId) && submittedCaseById.has(caseId), `mapping has unknown caseId: ${caseId}`);
  const candidate = candidateById.get(String(mapped.candidateId));
  check(candidate, `mapping references unknown candidate: ${mapped.candidateId}`);
  const material = submittedCaseById.get(caseId).material;
  check(material.title === (candidate.title || '')
    && material.snippet === (candidate.snippet || '')
    && material.canonicalUrl === (candidate.canonicalUrl || candidate.originalUrl || '')
    && material.publishedAtHint === (candidate.publishedAtHint || null),
  `mapped candidate material mismatch: ${caseId}`);
  for (const field of ['providerId', 'selectionStatus', 'selectionScore', 'selectionReason', 'queryLane', 'storyId']) {
    check(same(mapped[field], candidate[field]), `mapping ${field} mismatch: ${caseId}`);
  }
}

for (const field of ['schemaVersion', 'status', 'window', 'independenceRequirement']) {
  check(same(referenceEvents[field], referenceTemplate[field]), `submitted reference events changed ${field}`);
}
check(referenceEvents.status === 'DRAFT_EMPTY', 'legacy reference worksheet status must remain unchanged');
check(text(referenceEvents.collector) && utc(referenceEvents.frozenAt),
  'reference collector/frozenAt are required');
check(Array.isArray(referenceEvents.events) && referenceEvents.events.length > 0,
  'reference events must not be empty');
const goldById = uniqueMap(referenceEvents.events, 'eventId', 'reference events');
const windowStart = Date.parse(referenceEvents.window.start);
const windowEnd = Date.parse(referenceEvents.window.end);
let previousPublishedAt = -Infinity;
referenceEvents.events.forEach((event, index) => {
  exactKeys(event, ['eventId', 'title', 'firstPublishedAt', 'importance', 'slices'],
    `reference event ${event.eventId}`);
  check(/^gold-\d{8}-\d{3}$/.test(event.eventId)
    && Number(event.eventId.slice(-3)) === index + 1, `reference event IDs must be ordered and continuous`);
  check(text(event.title) && utc(event.firstPublishedAt), `reference event core fields invalid: ${event.eventId}`);
  const publishedAt = Date.parse(event.firstPublishedAt);
  check(publishedAt >= windowStart && publishedAt < windowEnd,
    `reference event outside half-open window: ${event.eventId}`);
  check(publishedAt >= previousPublishedAt, `reference events are not time ordered: ${event.eventId}`);
  previousPublishedAt = publishedAt;
  check(Number.isInteger(event.importance) && event.importance >= 1 && event.importance <= 3,
    `invalid importance: ${event.eventId}`);
  exactKeys(event.slices, ['category', 'language', 'sourceFamily'], `slices ${event.eventId}`);
  check(CATEGORIES.includes(event.slices.category) && LANGUAGES.includes(event.slices.language)
    && SOURCE_FAMILIES.includes(event.slices.sourceFamily), `invalid slices: ${event.eventId}`);
});

exactKeys(qc, ['schemaVersion', 'status', 'originalManifestSha256', 'annotationsSha256',
  'referenceEventsSha256', 'reviewerTiming', 'referenceAudit'], 'QC submission');
check(qc.schemaVersion === 'ai-news-candidate-shadow-qc-v1' && qc.status === 'COMPLETE',
  'QC submission must be COMPLETE');
check(qc.originalManifestSha256 === fileHash(manifestFile), 'QC original-manifest SHA-256 mismatch');
check(qc.annotationsSha256 === fileHash(annotationsFile), 'QC annotations SHA-256 mismatch');
check(qc.referenceEventsSha256 === fileHash(referenceEventsFile), 'QC reference-events SHA-256 mismatch');

const timing = qc.reviewerTiming;
exactKeys(timing, ['reviewerId', 'recordedStartedAt', 'recordedCompletedAt', 'recordedTimestampsMeaning',
  'actualReviewWindow', 'explanation', 'attestsAccurate', 'attestedAt'], 'reviewer timing clarification');
check(timing.reviewerId === annotations.reviewer.reviewerId
  && timing.recordedStartedAt === annotations.reviewer.startedAt
  && timing.recordedCompletedAt === annotations.reviewer.completedAt,
  'reviewer timing clarification does not identify the submitted worksheet');
check(['FULL_REVIEW', 'FINAL_ENTRY_ONLY'].includes(timing.recordedTimestampsMeaning),
  'recordedTimestampsMeaning must be FULL_REVIEW or FINAL_ENTRY_ONLY');
check(text(timing.explanation) && timing.attestsAccurate === true && utc(timing.attestedAt),
  'reviewer timing clarification is incomplete');
if (timing.actualReviewWindow !== null) {
  exactKeys(timing.actualReviewWindow, ['startAt', 'endAt'], 'actual review window');
  check(utc(timing.actualReviewWindow.startAt) && utc(timing.actualReviewWindow.endAt)
    && Date.parse(timing.actualReviewWindow.startAt) <= Date.parse(timing.actualReviewWindow.endAt),
    'actual review window must contain ordered UTC instants');
}
if (timing.recordedTimestampsMeaning === 'FULL_REVIEW') {
  check(timing.actualReviewWindow !== null
    && timing.actualReviewWindow.startAt === annotations.reviewer.startedAt
    && timing.actualReviewWindow.endAt === annotations.reviewer.completedAt,
  'FULL_REVIEW clarification must repeat the recorded review window');
}

const audit = qc.referenceAudit;
exactKeys(audit, ['collector', 'window', 'collectionStartedAt', 'collectionCompletedAt',
  'coverageCutoffAt', 'sourceUniverse', 'policies', 'events'], 'reference audit');
check(same(audit.window, referenceEvents.window), 'reference audit window mismatch');
check(utc(audit.collectionStartedAt) && utc(audit.collectionCompletedAt)
  && Date.parse(audit.collectionStartedAt) <= Date.parse(audit.collectionCompletedAt),
  'reference collection timestamps must be ordered UTC instants');
check(utc(audit.coverageCutoffAt) && Date.parse(audit.coverageCutoffAt) >= windowEnd
  && Date.parse(audit.coverageCutoffAt) <= Date.parse(audit.collectionCompletedAt),
  'reference coverage cutoff must cover the window and precede collection completion');
check(Date.parse(audit.collectionCompletedAt) <= Date.parse(referenceEvents.frozenAt),
  'reference events were frozen before collection completed');

const collector = audit.collector;
exactKeys(collector, ['collectorId', 'implementation', 'operator', 'attestsIndependentOfNewsClawDiscovery',
  'attestsNoCandidateExposureBeforeFreeze', 'attestsNoMappingExposureBeforeFreeze',
  'attestsPatrolCompleteThroughCutoff', 'attestsAllKnownSourcesRecorded', 'attestedAt', 'explanation'],
  'collector attestation');
check(collector.collectorId === referenceEvents.collector && text(collector.implementation)
  && text(collector.operator) && utc(collector.attestedAt) && text(collector.explanation),
  'collector identity/attestation metadata is incomplete');
for (const field of ['attestsIndependentOfNewsClawDiscovery', 'attestsNoCandidateExposureBeforeFreeze',
  'attestsNoMappingExposureBeforeFreeze', 'attestsPatrolCompleteThroughCutoff',
  'attestsAllKnownSourcesRecorded']) {
  check(collector[field] === true, `collector attestation must be true: ${field}`);
}

const universe = audit.sourceUniverse;
exactKeys(universe, ['frozenAt', 'includedSources', 'collectionLog'], 'source universe');
check(utc(universe.frozenAt) && Date.parse(universe.frozenAt) <= Date.parse(referenceEvents.frozenAt),
  'source universe must be frozen before reference events');
check(Array.isArray(universe.includedSources) && universe.includedSources.length > 0,
  'source universe must include at least one source');
const sourceById = uniqueMap(universe.includedSources, 'sourceId', 'source universe');
for (const source of universe.includedSources) {
  exactKeys(source, ['sourceId', 'publisher', 'endpoint', 'sourceFamily', 'languages', 'categories'],
    `source universe ${source.sourceId}`);
  check(text(source.publisher) && httpUrl(source.endpoint) && SOURCE_FAMILIES.includes(source.sourceFamily),
    `invalid source universe entry: ${source.sourceId}`);
  check([...stringSet(source.languages, `languages ${source.sourceId}`)].every(value => LANGUAGES.includes(value)),
    `invalid source language: ${source.sourceId}`);
  check([...stringSet(source.categories, `categories ${source.sourceId}`)].every(value => CATEGORIES.includes(value)),
    `invalid source category: ${source.sourceId}`);
}
check(Array.isArray(universe.collectionLog) && universe.collectionLog.length > 0,
  'source-universe patrol/query log is required');
for (const [index, entry] of universe.collectionLog.entries()) {
  exactKeys(entry, ['kind', 'occurredAt', 'details'], `collection log ${index}`);
  check(['source_patrol', 'search_query', 'manual_discovery'].includes(entry.kind)
    && utc(entry.occurredAt) && text(entry.details), `invalid collection log entry ${index}`);
}

const policies = audit.policies;
exactKeys(policies, ['frozenAt', 'eventBoundary', 'importance', 'slices'], 'reference policies');
check(utc(policies.frozenAt) && Date.parse(policies.frozenAt) <= Date.parse(referenceEvents.frozenAt),
  'reference policies must be frozen before reference events');
exactKeys(policies.eventBoundary, ['version', 'rules'], 'event-boundary policy');
check(text(policies.eventBoundary.version)
  && stringSet(policies.eventBoundary.rules, 'event-boundary rules').size > 0,
  'event-boundary policy is incomplete');
exactKeys(policies.importance, ['version', 'levels'], 'importance policy');
check(text(policies.importance.version), 'importance policy version is required');
exactKeys(policies.importance.levels, ['1', '2', '3'], 'importance levels');
check(Object.values(policies.importance.levels).every(text), 'importance level definitions are required');
exactKeys(policies.slices, ['version', 'categories', 'languages', 'sourceFamilies',
  'languageMeaning', 'sourceFamilyMeaning'], 'slice policy');
check(text(policies.slices.version) && text(policies.slices.languageMeaning)
  && text(policies.slices.sourceFamilyMeaning), 'slice policy semantics are required');
check(same(policies.slices.categories, CATEGORIES)
  && same(policies.slices.languages, LANGUAGES)
  && same(policies.slices.sourceFamilies, SOURCE_FAMILIES), 'slice vocabulary drifted');

const evidenceByEventId = uniqueMap(audit.events, 'eventId', 'reference evidence');
check(evidenceByEventId.size === goldById.size, 'reference evidence must cover every event exactly once');
for (const [eventId, event] of goldById) {
  const evidence = evidenceByEventId.get(eventId);
  check(evidence, `missing reference evidence: ${eventId}`);
  exactKeys(evidence, ['eventId', 'sources', 'firstPublishedAtEvidenceUrl', 'boundary', 'conflictNotes'],
    `reference evidence ${eventId}`);
  check(Array.isArray(evidence.sources) && evidence.sources.length > 0,
    `reference evidence has no sources: ${eventId}`);
  const sourceUrls = new Set();
  for (const source of evidence.sources) {
    exactKeys(source, ['sourceId', 'url', 'publisher', 'sourceFamily', 'language', 'publishedAt',
      'rawPublishedAt', 'publishedAtKind', 'timePrecision'], `source evidence ${eventId}`);
    check(httpUrl(source.url) && !sourceUrls.has(source.url), `invalid/duplicate source URL: ${eventId}`);
    sourceUrls.add(source.url);
    const declared = sourceById.get(source.sourceId);
    check(declared && source.publisher === declared.publisher
      && source.sourceFamily === declared.sourceFamily
      && declared.languages.includes(source.language)
      && declared.categories.includes(event.slices.category), `source not in frozen universe: ${eventId}`);
    check(SOURCE_FAMILIES.includes(source.sourceFamily) && LANGUAGES.includes(source.language),
      `invalid source slice: ${eventId}`);
    check(['original', 'updated', 'unknown'].includes(source.publishedAtKind)
      && ['second', 'minute', 'day', 'unknown'].includes(source.timePrecision)
      && text(source.rawPublishedAt), `source time provenance is incomplete: ${eventId}`);
    check(source.publishedAt === null || utc(source.publishedAt), `source publishedAt is invalid: ${eventId}`);
    if (source.publishedAtKind === 'original') {
      check(utc(source.publishedAt) && Date.parse(source.publishedAt) >= Date.parse(event.firstPublishedAt),
        `source predates declared firstPublishedAt: ${eventId}`);
    }
  }
  check(sourceUrls.has(evidence.firstPublishedAtEvidenceUrl),
    `firstPublishedAt evidence URL is not in sources: ${eventId}`);
  const proof = evidence.sources.find(source => source.url === evidence.firstPublishedAtEvidenceUrl);
  check(proof.publishedAtKind === 'original' && proof.publishedAt === event.firstPublishedAt
    && proof.timePrecision !== 'unknown', `earliest-time proof does not support firstPublishedAt: ${eventId}`);
  check(proof.language === event.slices.language && proof.sourceFamily === event.slices.sourceFamily,
    `first-publication proof does not support language/sourceFamily slices: ${eventId}`);
  check(typeof evidence.conflictNotes === 'string', `conflictNotes must be a string: ${eventId}`);
  const boundary = evidence.boundary;
  exactKeys(boundary, ['risk', 'decision', 'relatedEventIds', 'reason', 'adjudicatedBy', 'adjudicatedAt',
    'secondaryAdjudication'], `event boundary ${eventId}`);
  check(['standard', 'high'].includes(boundary.risk) && boundary.decision === 'KEEP'
    && text(boundary.reason) && text(boundary.adjudicatedBy) && utc(boundary.adjudicatedAt),
  `event boundary is not finally adjudicated KEEP: ${eventId}`);
  check(!REQUIRED_SECONDARY_ADJUDICATION_IDS.has(eventId) || boundary.risk === 'high',
    `known Day-1 high-risk event requires secondary adjudication: ${eventId}`);
  const related = stringSet(boundary.relatedEventIds, `relatedEventIds ${eventId}`);
  check([...related].every(id => id !== eventId && goldById.has(id)),
    `event boundary references unknown/self event: ${eventId}`);
  if (boundary.risk === 'high') {
    const second = boundary.secondaryAdjudication;
    exactKeys(second, ['decision', 'reason', 'adjudicatedBy', 'adjudicatedAt'],
      `secondary event-boundary adjudication ${eventId}`);
    check(second.decision === 'KEEP' && text(second.reason) && text(second.adjudicatedBy)
      && second.adjudicatedBy !== boundary.adjudicatedBy && utc(second.adjudicatedAt),
    `high-risk event lacks independent secondary KEEP adjudication: ${eventId}`);
  } else {
    check(boundary.secondaryAdjudication === null,
      `standard-risk event must not contain secondary adjudication: ${eventId}`);
  }
}

exactKeys(adjudications, ['schemaVersion', 'status', 'snapshotHash', 'referenceEventsSha256',
  'adjudicator', 'candidates'], 'candidate adjudications');
check(adjudications.schemaVersion === 'ai-news-candidate-adjudications-v1'
  && adjudications.status === 'COMPLETE', 'candidate adjudications must be COMPLETE');
check(adjudications.snapshotHash === snapshot.snapshotHash, 'candidate adjudication snapshotHash mismatch');
check(adjudications.referenceEventsSha256 === fileHash(referenceEventsFile),
  'candidate adjudication reference-events SHA-256 mismatch');
exactKeys(adjudications.adjudicator,
  ['adjudicatorId', 'startedAt', 'completedAt', 'attestsHumanAdjudication',
    'attestsReferenceSetFrozenBeforeCandidateExposure'], 'candidate adjudicator');
check(text(adjudications.adjudicator.adjudicatorId) && utc(adjudications.adjudicator.startedAt)
  && utc(adjudications.adjudicator.completedAt)
  && Date.parse(adjudications.adjudicator.startedAt) <= Date.parse(adjudications.adjudicator.completedAt)
  && adjudications.adjudicator.attestsHumanAdjudication === true
  && adjudications.adjudicator.attestsReferenceSetFrozenBeforeCandidateExposure === true,
  'candidate adjudicator attestation is incomplete');
check(Date.parse(universe.frozenAt) <= Date.parse(adjudications.adjudicator.startedAt)
  && Date.parse(policies.frozenAt) <= Date.parse(adjudications.adjudicator.startedAt)
  && Date.parse(referenceEvents.frozenAt) <= Date.parse(adjudications.adjudicator.startedAt),
  'candidate adjudication started before the reference set/source universe/policies were frozen');
const adjudicationByCandidateId = uniqueMap(adjudications.candidates, 'candidateId', 'candidate adjudications');
check(adjudicationByCandidateId.size === candidateById.size,
  `candidate adjudications ${adjudicationByCandidateId.size} != exported candidates ${candidateById.size}`);
for (const [candidateId, candidate] of candidateById) {
  const label = adjudicationByCandidateId.get(candidateId);
  check(label, `missing candidate adjudication: ${candidateId}`);
  exactKeys(label, ['candidateId', 'status', 'matchedGoldEventId', 'adjudicationReason'],
    `candidate adjudication ${candidateId}`);
  check(['MATCHED', 'NO_MATCH', 'UNRESOLVED'].includes(label.status),
    `invalid candidate adjudication status: ${candidateId}`);
  check(label.matchedGoldEventId === null || goldById.has(label.matchedGoldEventId),
    `candidate references unknown gold event: ${candidateId}`);
  check((label.status === 'MATCHED') === (label.matchedGoldEventId !== null),
    `candidate MATCHED status/gold ID mismatch: ${candidateId}`);
  check(label.status !== 'UNRESOLVED',
    `candidate remains UNRESOLVED and cannot be scored safely: ${candidateId}`);
  check(text(label.adjudicationReason), `candidate adjudicationReason is required: ${candidateId}`);
  check(rankByCandidateId.has(candidateId), `candidate rank is missing: ${candidateId}`);
  check(text(candidate.title) && text(candidate.canonicalUrl || candidate.originalUrl),
    `candidate scorer material is incomplete: ${candidateId}`);
  check((candidate.sourceClass === null || candidate.sourceClass === undefined
    || typeof candidate.sourceClass === 'string')
    && (candidate.publishedAtHint === null || candidate.publishedAtHint === undefined
      || typeof candidate.publishedAtHint === 'string'),
  `candidate sourceClass/publishedAtHint type is invalid: ${candidateId}`);
}

const rankedCandidates = [...candidateById.values()].sort((left, right) =>
  rankByCandidateId.get(String(left.id)) - rankByCandidateId.get(String(right.id)));
const discoveryCandidates = rankedCandidates.map(candidate => {
  const label = adjudicationByCandidateId.get(String(candidate.id));
  return {
    candidateId: String(candidate.id),
    rank: rankByCandidateId.get(String(candidate.id)),
    title: candidate.title,
    url: candidate.canonicalUrl || candidate.originalUrl,
    sourceClass: candidate.sourceClass || null,
    publishedAtHint: candidate.publishedAtHint || null,
    matchedGoldEventId: label.matchedGoldEventId,
    adjudicationReason: label.adjudicationReason
  };
});
const selectedCutoffs = [...new Set([Math.min(5, selectedSnapshot.length), selectedSnapshot.length])]
  .filter(value => value > 0).sort((left, right) => left - right);
const dataset = {
  schemaVersion: '1.0',
  datasetId: `candidate-shadow-${run.id}`,
  datasetVersion: snapshot.snapshotHash.slice(0, 16),
  evaluationScope: 'candidate-only-operational-shadow-provisional',
  window: {
    startAt: referenceEvents.window.start,
    endAt: referenceEvents.window.end,
    observationEndAt: audit.collectionCompletedAt
  },
  config: {
    freshnessCutoffsMinutes: [1],
    rankingCutoffs: selectedCutoffs,
    latencyStepMinutes: 60,
    rankingLookbackMinutes: 1440,
    earlyDetectionToleranceMinutes: 0
  },
  executionMetadata: {
    evaluationEligible: 'false',
    labelReviewStatus: 'single-reviewer-operational-shadow',
    sourceUniverse: 'FROZEN_UNAPPROVED',
    independentCollector: 'true',
    futureLeakage: 'UNVERIFIED_DAY1',
    splits: 'DAY1_DIAGNOSTIC',
    candidateAdjudicationStatus: 'complete',
    candidateUniverseItemCount: String(candidateById.size),
    candidateRankSemantics: 'snapshot-selected-first-then-stable-id-tail',
    selectedCandidateCount: String(selectedSnapshot.length),
    reviewedCandidateCases: String(annotations.cases.length),
    referenceSetStatus: 'PROVISIONAL_SINGLE_COLLECTOR'
  },
  goldEvents: referenceEvents.events.map(event => ({
    eventId: event.eventId,
    title: event.title,
    firstPublishedAt: event.firstPublishedAt,
    importance: event.importance,
    slices: event.slices
  })),
  discoveryCandidates,
  systemEvents: [],
  clusterAssignments: [],
  rankingSnapshots: [],
  limitations: [
    'Single-reviewer operational shadow; not a formal double-blind benchmark.',
    'Day-1 is diagnostic/calibration data and is not a sealed holdout.',
    'Candidate capture, system events, clustering, evidence readiness and publication were not executed.',
    'Only snapshot-selected ranks have ranking semantics; the unselected tail uses stable candidate-ID order.',
    'Null matchedGoldEventId values retain their human adjudication reason and are not open-world negative labels.'
  ]
};
check(Date.parse(dataset.window.observationEndAt) >= windowEnd + 60_000,
  'collectionCompletedAt must support the configured 1-minute observation tail');

const reviewedCandidates = {
  schemaVersion: 'ai-news-candidate-shadow-reviewed-join-v1',
  scanRunId: String(run.id),
  discoveryRunId: String(run.discoveryRunId),
  snapshotHash: snapshot.snapshotHash,
  cases: mapping.cases.map(mapped => {
    const candidate = candidateById.get(String(mapped.candidateId));
    const review = submittedCaseById.get(mapped.caseId);
    const label = adjudicationByCandidateId.get(String(mapped.candidateId));
    return {
      caseId: mapped.caseId,
      candidateId: String(mapped.candidateId),
      rank: rankByCandidateId.get(String(mapped.candidateId)),
      providerId: mapped.providerId,
      stratum: mapped.stratum,
      selectionStatus: mapped.selectionStatus,
      selectionScore: mapped.selectionScore,
      selectionReason: mapped.selectionReason,
      queryLane: mapped.queryLane,
      storyId: mapped.storyId,
      material: review.material,
      annotation: review.annotation,
      matchedGoldEventId: label.matchedGoldEventId,
      adjudicationReason: label.adjudicationReason,
      sourceClass: candidate.sourceClass || null
    };
  })
};

const outputFiles = new Map([
  ['inputs/annotations.json', readBytes(annotationsFile)],
  ['inputs/reference-events.json', readBytes(referenceEventsFile)],
  ['inputs/qc.json', readBytes(qcFile)],
  ['inputs/candidate-adjudications.json', readBytes(adjudicationsFile)],
  ['reviewed-candidates.json', jsonBytes(reviewedCandidates)],
  ['adjudicated-discovery-dataset.json', jsonBytes(dataset)]
]);
const outputHashes = Object.fromEntries([...outputFiles].map(([name, bytes]) => [name, sha256(bytes)]));
const submissionManifest = {
  schemaVersion: 'ai-news-candidate-shadow-finalized-submission-v1',
  status: 'FINALIZED_OPERATIONAL_SHADOW',
  evaluationEligible: false,
  generatedAt: new Date().toISOString(),
  tool: {
    name: path.basename(__filename),
    version: TOOL_VERSION,
    gitSha: git(['rev-parse', 'HEAD']),
    gitDirty: git(['status', '--porcelain=v1', '--untracked-files=all']).length > 0
  },
  sourcePackage: {
    day1Root: displayPath(dayRoot),
    manifestSha256: fileHash(manifestFile),
    scanRunId: String(run.id),
    discoveryRunId: String(run.discoveryRunId),
    snapshotHash: snapshot.snapshotHash,
    rankingHash: snapshot.rankingHash,
    rankingPolicyVersion: snapshot.rankingPolicyVersion,
    files: manifest.source.files,
    packageOutputs: manifest.outputs
  },
  submittedInputs: {
    directory: displayPath(submissionDir),
    files: {
      'annotations.json': fileHash(annotationsFile),
      'reference-events.json': fileHash(referenceEventsFile),
      'qc.json': fileHash(qcFile),
      'candidate-adjudications.json': fileHash(adjudicationsFile)
    }
  },
  integrity: {
    issuedReviewCases: originalWorksheet.cases.length,
    joinedReviewCases: reviewedCandidates.cases.length,
    referenceEvents: goldById.size,
    adjudicatedCandidates: candidateById.size,
    selectedCandidates: selectedSnapshot.length,
    materialUnchanged: true,
    strictReviewMappingJoin: true,
    strictCandidateUniverseJoin: true
  },
  outputs: outputHashes,
  blockers: [
    'single reviewer; no second independent reviewer or adjudication',
    'Day-1 diagnostic/calibration window; not a sealed holdout',
    'capture/system-event/normalize-dedupe/publish families were not executed'
  ]
};
const manifestBytes = jsonBytes(submissionManifest);
outputFiles.set('submission-manifest.json', manifestBytes);
outputFiles.set('submission-manifest.sha256',
  Buffer.from(`${sha256(manifestBytes)}  submission-manifest.json\n`));

check(!fs.existsSync(outputDir), `refusing to overwrite output directory: ${outputDir}`);
fs.mkdirSync(path.dirname(outputDir), {recursive: true});
const temporaryOutputDir = `${outputDir}.tmp-${process.pid}-${Date.now()}`;
check(!fs.existsSync(temporaryOutputDir), `temporary output path already exists: ${temporaryOutputDir}`);
try {
  fs.mkdirSync(temporaryOutputDir);
  for (const [name, bytes] of outputFiles) {
    const file = path.join(temporaryOutputDir, name);
    fs.mkdirSync(path.dirname(file), {recursive: true});
    fs.writeFileSync(file, bytes, {flag: 'wx'});
    if (fileHash(file) !== sha256(bytes)) throw new Error(`written output hash mismatch: ${name}`);
  }
  if (fs.existsSync(outputDir)) throw new Error(`output directory appeared during finalization: ${outputDir}`);
  fs.renameSync(temporaryOutputDir, outputDir);
} catch (error) {
  fs.rmSync(temporaryOutputDir, {recursive: true, force: true});
  fail(`failed to write finalized output atomically: ${error.message}`);
}

process.stdout.write(`AI_NEWS_CANDIDATE_FINALIZED=${JSON.stringify({
  output: outputDir,
  status: submissionManifest.status,
  evaluationEligible: false,
  reviewCases: reviewedCandidates.cases.length,
  referenceEvents: goldById.size,
  candidates: candidateById.size,
  selected: selectedSnapshot.length,
  manifestSha256: sha256(manifestBytes)
})}\n`);
