#!/usr/bin/env node
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
const requireReady = args.includes('--require-ready');
const positional = args.filter(value => value !== '--require-ready');
const manifestPath = path.resolve(positional[0] || path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/domain-gold/campaign-draft-v1.json'));
const outputPath = positional[1] ? path.resolve(positional[1]) : null;

function fail(message) {
  process.stderr.write(`AI_NEWS_DOMAIN_GOLD_INVALID ${message}\n`);
  process.exit(1);
}

function sha256Bytes(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key =>
      `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function utc(value) {
  return typeof value === 'string' && /Z$/.test(value) && Number.isFinite(Date.parse(value));
}

function repositoryFile(relativePath, label, errors) {
  if (typeof relativePath !== 'string' || !relativePath.trim()) {
    errors.push(`${label} must be a repository path`);
    return null;
  }
  const resolved = path.resolve(root, relativePath);
  if (resolved === root || !resolved.startsWith(`${root}${path.sep}`)) {
    errors.push(`${label} escapes repository: ${relativePath}`);
    return null;
  }
  if (!fs.existsSync(resolved)) {
    errors.push(`${label} is missing: ${relativePath}`);
    return null;
  }
  return resolved;
}

function schemaPropertyNames(value, result = new Set()) {
  if (Array.isArray(value)) {
    for (const item of value) schemaPropertyNames(item, result);
    return result;
  }
  if (!value || typeof value !== 'object') return result;
  if (value.properties && typeof value.properties === 'object' && !Array.isArray(value.properties)) {
    for (const key of Object.keys(value.properties)) result.add(key);
  }
  for (const child of Object.values(value)) schemaPropertyNames(child, result);
  return result;
}

if (!fs.existsSync(manifestPath)) fail(`manifest not found: ${manifestPath}`);
const bytes = fs.readFileSync(manifestPath);
let campaign;
try {
  campaign = JSON.parse(bytes.toString('utf8'));
} catch (error) {
  fail(`invalid JSON: ${error.message}`);
}

const structuralErrors = [];
const blockers = new Map();
const block = (code, detail) => {
  if (!blockers.has(code)) blockers.set(code, []);
  blockers.get(code).push(detail);
};
const requiredStrings = ['schemaVersion', 'campaignId', 'campaignStatus', 'purpose'];
for (const field of requiredStrings) {
  if (typeof campaign[field] !== 'string' || !campaign[field].trim()) {
    structuralErrors.push(`${field} must be a non-empty string`);
  }
}
if (campaign.schemaVersion !== '1.0') structuralErrors.push('schemaVersion must be 1.0');
if (!['DRAFT_BLOCKED', 'READY', 'COLLECTING', 'OBSERVATION_TAIL', 'FROZEN', 'ADJUDICATING',
  'COMPLETE'].includes(campaign.campaignStatus)) {
  structuralErrors.push(`unsupported campaignStatus: ${campaign.campaignStatus}`);
}

const frozenInputs = Array.isArray(campaign.frozenInputs) ? campaign.frozenInputs : [];
if (frozenInputs.length === 0) structuralErrors.push('frozenInputs must not be empty');
const frozenPaths = new Set();
for (const item of frozenInputs) {
  if (!item || typeof item.path !== 'string' || !/^[a-f0-9]{64}$/.test(item.sha256 || '')) {
    structuralErrors.push('every frozen input requires a repository path and SHA-256');
    continue;
  }
  if (frozenPaths.has(item.path)) structuralErrors.push(`duplicate frozen input: ${item.path}`);
  frozenPaths.add(item.path);
  const resolved = path.resolve(root, item.path);
  if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) {
    structuralErrors.push(`frozen input escapes repository: ${item.path}`);
  } else if (!fs.existsSync(resolved)) {
    structuralErrors.push(`frozen input is missing: ${item.path}`);
  } else {
    const actual = sha256Bytes(fs.readFileSync(resolved));
    if (actual !== item.sha256) structuralErrors.push(`frozen input hash drift: ${item.path}`);
  }
}

const contracts = campaign.dataContracts || {};
const poolSchemaPath = contracts.coordinatorPoolSchemaPath;
const blindSchemaPath = contracts.blindWorksheetSchemaPath;
const forbiddenBlindNames = Array.isArray(contracts.forbiddenBlindPropertyNames)
  ? contracts.forbiddenBlindPropertyNames : [];
for (const [label, relativePath] of [
  ['coordinator pool schema', poolSchemaPath],
  ['blind worksheet schema', blindSchemaPath]
]) {
  const resolved = repositoryFile(relativePath, label, structuralErrors);
  if (!resolved) continue;
  if (!frozenPaths.has(relativePath)) structuralErrors.push(`${label} must be a frozen input`);
  let schema;
  try {
    schema = JSON.parse(fs.readFileSync(resolved, 'utf8'));
  } catch (error) {
    structuralErrors.push(`${label} is invalid JSON: ${error.message}`);
    continue;
  }
  if (schema.$schema !== 'https://json-schema.org/draft/2020-12/schema'
      || schema.type !== 'object' || schema.additionalProperties !== false) {
    structuralErrors.push(`${label} must be a closed JSON Schema 2020-12 object`);
  }
  const names = schemaPropertyNames(schema);
  if (label === 'coordinator pool schema') {
    for (const requiredName of ['coordinatorOnly', 'poolDiscoveries',
      'systemUnderTestPrediction', 'predictedClusterId', 'predictedClusterVersion']) {
      if (!names.has(requiredName)) structuralErrors.push(`${label} lacks ${requiredName}`);
    }
  } else {
    if (forbiddenBlindNames.length === 0) {
      structuralErrors.push('forbiddenBlindPropertyNames must not be empty');
    }
    for (const name of forbiddenBlindNames) {
      if (names.has(name)) structuralErrors.push(`${label} leaks forbidden property: ${name}`);
    }
  }
}

const window = campaign.window || {};
if (!utc(window.startAt) || !utc(window.endAt) || !utc(window.observationEndAt)) {
  block('WINDOW_NOT_FROZEN', 'startAt/endAt/observationEndAt must be explicit UTC instants');
} else {
  const durationDays = (Date.parse(window.endAt) - Date.parse(window.startAt)) / 86400000;
  const tailHours = (Date.parse(window.observationEndAt) - Date.parse(window.endAt)) / 3600000;
  if (durationDays < Number(window.minimumNaturalDays || 14)) {
    block('WINDOW_NOT_FROZEN', `duration ${durationDays}d is below the declared minimum`);
  }
  if (tailHours < Number(window.observationTailHours || 24)) {
    block('WINDOW_NOT_FROZEN', `observation tail ${tailHours}h is below the declared minimum`);
  }
}

const sources = Array.isArray(campaign.sourceUniverse) ? campaign.sourceUniverse : [];
const endpointIds = new Set();
const approved = [];
for (const source of sources) {
  if (!source || typeof source.endpointId !== 'string' || !source.endpointId.trim()) {
    structuralErrors.push('source endpointId is required');
    continue;
  }
  if (endpointIds.has(source.endpointId)) structuralErrors.push(`duplicate endpointId: ${source.endpointId}`);
  endpointIds.add(source.endpointId);
  if (!['official', 'media', 'community'].includes(source.sourceFamily)) {
    structuralErrors.push(`${source.endpointId}: invalid sourceFamily`);
  }
  if (!Array.isArray(source.languages) || source.languages.length === 0
      || !Array.isArray(source.categories) || source.categories.length === 0) {
    structuralErrors.push(`${source.endpointId}: languages and categories are required`);
  }
  if (source.enabledForCollection === true && source.approvedForCampaign !== true) {
    structuralErrors.push(`${source.endpointId}: enabled source lacks campaign approval`);
  }
  if (source.approvedForCampaign === true) {
    if (source.rightsStatus !== 'approved' || source.robotsStatus !== 'approved'
        || typeof source.reviewOwner !== 'string' || !source.reviewOwner.trim()
        || !utc(source.reviewedAt)) {
      structuralErrors.push(`${source.endpointId}: approval requires rights/robots approval, owner and UTC review time`);
    }
  }
  if (source.enabledForCollection === true) approved.push(source);
}
if (approved.length === 0) {
  block('SOURCE_UNIVERSE_NOT_APPROVED', 'no reviewed source endpoint is enabled for collection');
}

const requiredCoverage = campaign.requiredCoverage || {};
const missingLanguages = (requiredCoverage.languages || []).filter(language =>
  !approved.some(source => source.languages.includes(language)));
if (missingLanguages.length) block('LANGUAGE_COVERAGE_MISSING', missingLanguages.join(','));
const missingFamilies = (requiredCoverage.sourceFamilies || []).filter(family =>
  !approved.some(source => source.sourceFamily === family));
if (missingFamilies.length) block('SOURCE_FAMILY_COVERAGE_MISSING', missingFamilies.join(','));
const missingCategories = (requiredCoverage.categories || []).filter(category =>
  !approved.some(source => source.categories.includes(category)));
if (missingCategories.length) block('CATEGORY_COVERAGE_MISSING', missingCategories.join(','));

const collection = campaign.collection || {};
if (!Number.isInteger(collection.pollIntervalMinutes) || collection.pollIntervalMinutes < 15
    || collection.pollIntervalMinutes > 30) {
  structuralErrors.push('pollIntervalMinutes must be an integer within [15,30]');
}
const collector = collection.independentCollector || {};
if (collector.enabled !== true || collector.independentOfNewsClawDiscovery !== true
    || !String(collector.collectorId || '').trim() || !String(collector.implementation || '').trim()
    || !String(collector.operator || '').trim()) {
  block('INDEPENDENT_COLLECTOR_NOT_READY', 'collector identity, implementation and operator are not frozen');
}

const pools = Array.isArray(campaign.poolingSystems) ? campaign.poolingSystems : [];
const enabledPools = pools.filter(item => item && item.enabled === true);
const enabledKinds = new Set(enabledPools.map(item => item.kind));
const requiredPoolKinds = ['system_under_test', 'independent_direct_source', 'independent_human_patrol'];
const invalidEnabledPools = enabledPools.filter(item =>
  !String(item.systemId || '').trim() || !String(item.operator || '').trim()
  || !String(item.outputContract || '').trim()
  || (item.kind !== 'system_under_test' && item.independentOfNewsClawDiscovery !== true));
if (requiredPoolKinds.some(kind => !enabledKinds.has(kind)) || enabledKinds.size < 3
    || invalidEnabledPools.length > 0) {
  block('POOLING_NOT_READY', `enabled kinds=${[...enabledKinds].sort().join(',')}`);
}

const annotation = campaign.annotationProtocol || {};
const slots = annotation.reviewerSlots || {};
if (annotation.blindIndependentReviewers !== 2
    || annotation.hideSystemPredictionsFromReviewers !== true
    || annotation.unknownIsAllowed !== true
    || annotation.adjudicateEveryDisagreement !== true) {
  structuralErrors.push('annotation protocol must require two blind reviewers, unknown and adjudication');
}
if (!String(slots.reviewerA || '').trim() || !String(slots.reviewerB || '').trim()
    || slots.reviewerA === slots.reviewerB || !String(annotation.adjudicator || '').trim()
    || annotation.adjudicator === slots.reviewerA || annotation.adjudicator === slots.reviewerB) {
  block('ANNOTATION_STAFF_NOT_ASSIGNED', 'two distinct reviewer pseudonyms and one adjudicator are required');
}

const split = campaign.splitProtocol || {};
if (split.unit !== 'natural-time-window' || split.futureInformationForbidden !== true
    || split.sealedHoldoutSingleLook !== true) {
  structuralErrors.push('time split must prohibit future information and require a single-look holdout');
}
const splitGroups = [
  ['developmentWindows', split.developmentWindows],
  ['calibrationWindows', split.calibrationWindows],
  ['sealedHoldoutWindows', split.sealedHoldoutWindows]
];
const parsedSplitWindows = [];
let splitsReady = true;
for (const [group, ranges] of splitGroups) {
  if (!Array.isArray(ranges) || ranges.length === 0) {
    splitsReady = false;
    continue;
  }
  for (const range of ranges) {
    if (!range || !utc(range.startAt) || !utc(range.endAt)
        || Date.parse(range.startAt) >= Date.parse(range.endAt)) {
      splitsReady = false;
      continue;
    }
    const start = Date.parse(range.startAt);
    const end = Date.parse(range.endAt);
    if (utc(window.startAt) && utc(window.endAt)
        && (start < Date.parse(window.startAt) || end > Date.parse(window.endAt))) {
      splitsReady = false;
    }
    parsedSplitWindows.push({group, start, end});
  }
}
parsedSplitWindows.sort((a, b) => a.start - b.start || a.end - b.end);
for (let index = 1; index < parsedSplitWindows.length; index += 1) {
  if (parsedSplitWindows[index].start < parsedSplitWindows[index - 1].end) splitsReady = false;
}
if (!splitsReady) {
  block('SPLITS_NOT_FROZEN', 'development, calibration and sealed holdout ranges must be non-empty, valid, disjoint and inside the campaign window');
}

const blockerCodes = [...blockers.keys()].sort();
const ready = structuralErrors.length === 0 && blockerCodes.length === 0;
const expected = campaign.expectedReadiness || {};
if (typeof expected.ready !== 'boolean' || expected.ready !== ready) {
  structuralErrors.push(`expectedReadiness.ready drift: expected=${expected.ready}, actual=${ready}`);
}
const expectedCodes = Array.isArray(expected.blockerCodes) ? [...expected.blockerCodes].sort() : [];
if (canonical(expectedCodes) !== canonical(blockerCodes)) {
  structuralErrors.push(`expected blocker drift: expected=${expectedCodes}, actual=${blockerCodes}`);
}

const report = {
  schemaVersion: '1.0',
  campaignId: campaign.campaignId,
  campaignStatus: campaign.campaignStatus,
  manifestPath: path.relative(root, manifestPath),
  manifestSha256: sha256Bytes(bytes),
  structurallyValid: structuralErrors.length === 0,
  ready,
  counts: {
    declaredSources: sources.length,
    approvedEnabledSources: approved.length,
    enabledPoolSystems: pools.filter(item => item && item.enabled === true).length
  },
  coverage: {
    languages: [...new Set(approved.flatMap(item => item.languages || []))].sort(),
    sourceFamilies: [...new Set(approved.map(item => item.sourceFamily))].sort(),
    categories: [...new Set(approved.flatMap(item => item.categories || []))].sort()
  },
  blockers: Object.fromEntries([...blockers.entries()].sort(([a], [b]) => a.localeCompare(b))),
  structuralErrors,
  limitations: campaign.limitations || []
};

if (outputPath) {
  fs.mkdirSync(path.dirname(outputPath), {recursive: true});
  fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`);
}
process.stdout.write(`AI_NEWS_DOMAIN_GOLD_READINESS=${JSON.stringify(report)}\n`);
if (structuralErrors.length) process.exit(1);
if (requireReady && !ready) process.exit(3);
