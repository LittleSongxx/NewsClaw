#!/usr/bin/env node
'use strict';

/**
 * Converts a coordinator-only pooled ledger into two independently ordered,
 * prediction-blind reviewer worksheets plus an adjudication mapping. The
 * production path is fail-closed unless the campaign was ready and collection
 * has been frozen. A synthetic contract fixture may exercise packaging while
 * the real campaign remains blocked, but is marked evaluation-ineligible.
 */

const crypto = require('crypto');
const childProcess = require('child_process');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
if (args.includes('--help') || args.length !== 3) {
  process.stdout.write('Usage: prepare-ai-news-domain-gold-review-package.js <campaign.json> <coordinator-pool.json> <new-output-directory>\n');
  process.exit(args.includes('--help') ? 0 : 2);
}

const campaignPath = path.resolve(args[0]);
const poolPath = path.resolve(args[1]);
const outputPath = path.resolve(args[2]);
const REVIEW_KEYS = new Set([
  'itemId', 'observedAt', 'publisher', 'sourceFamily', 'language', 'url',
  'canonicalUrl', 'title', 'publishedAt', 'publishedAtRaw', 'mainContent',
  'contentSha256', 'claims', 'quotes'
]);

function fail(message) {
  process.stderr.write(`AI_NEWS_DOMAIN_GOLD_PACKAGE_INVALID ${message}\n`);
  process.exit(1);
}

function readJson(file, label) {
  if (!fs.existsSync(file)) fail(`${label} not found: ${file}`);
  try {
    const bytes = fs.readFileSync(file);
    return {bytes, value: JSON.parse(bytes.toString('utf8'))};
  } catch (error) {
    fail(`${label} is invalid JSON: ${error.message}`);
  }
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function utc(value) {
  return typeof value === 'string' && /Z$/.test(value) && Number.isFinite(Date.parse(value));
}

function nonEmpty(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function httpUrl(value) {
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:';
  } catch (_error) {
    return false;
  }
}

function assertExactKeys(value, allowed, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${label} must be an object`);
  const unexpected = Object.keys(value).filter(key => !allowed.has(key));
  if (unexpected.length) fail(`${label} has unexpected properties: ${unexpected.join(',')}`);
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function collectPropertyNames(value, names = new Set()) {
  if (Array.isArray(value)) {
    for (const item of value) collectPropertyNames(item, names);
    return names;
  }
  if (!value || typeof value !== 'object') return names;
  for (const [key, child] of Object.entries(value)) {
    names.add(key);
    collectPropertyNames(child, names);
  }
  return names;
}

function collectStrings(value, strings = new Set()) {
  if (typeof value === 'string') {
    strings.add(value);
  } else if (Array.isArray(value)) {
    for (const item of value) collectStrings(item, strings);
  } else if (value && typeof value === 'object') {
    for (const child of Object.values(value)) collectStrings(child, strings);
  }
  return strings;
}

const campaignInput = readJson(campaignPath, 'campaign');
const poolInput = readJson(poolPath, 'coordinator pool');
const campaign = campaignInput.value;
const pool = poolInput.value;
const campaignHash = sha256(campaignInput.bytes);
const poolHash = sha256(poolInput.bytes);

if (campaign.schemaVersion !== '1.0' || pool.schemaVersion !== '1.0') {
  fail('campaign and coordinator pool schemaVersion must be 1.0');
}
if (!nonEmpty(campaign.campaignId) || pool.campaignId !== campaign.campaignId) {
  fail('coordinator pool campaignId does not match campaign');
}
if (pool.campaignManifestSha256 !== campaignHash) {
  fail(`campaign hash drift: pool=${pool.campaignManifestSha256}, actual=${campaignHash}`);
}
const classification = pool.datasetClassification;
const contractFixture = classification === 'synthetic-contract-fixture-not-evaluation';
const production = classification === 'production-domain-gold';
if (!contractFixture && !production) fail(`unsupported datasetClassification: ${classification}`);
if (production && (campaign.expectedReadiness?.ready !== true
    || !['FROZEN', 'ADJUDICATING'].includes(campaign.campaignStatus))) {
  fail('production pool requires a ready campaign in FROZEN or ADJUDICATING state');
}
if (production) {
  const readinessGate = childProcess.spawnSync(process.execPath, [
    path.join(root, 'scripts', 'validate-ai-news-domain-gold-campaign.js'),
    '--require-ready',
    campaignPath
  ], {encoding: 'utf8'});
  if (readinessGate.status !== 0) {
    const detail = String(readinessGate.stderr || readinessGate.stdout || '').trim();
    fail(`production campaign failed readiness gate${detail ? `: ${detail}` : ''}`);
  }
}
if (!Array.isArray(pool.systems) || !Array.isArray(pool.cases) || pool.cases.length === 0) {
  fail('coordinator pool requires systems and at least one case');
}
const kinds = new Set(pool.systems.map(item => item && item.kind));
for (const requiredKind of ['system_under_test', 'independent_direct_source', 'independent_human_patrol']) {
  if (!kinds.has(requiredKind)) fail(`coordinator pool lacks required system kind: ${requiredKind}`);
}
for (const system of pool.systems) {
  if (!system || !nonEmpty(system.systemId) || !nonEmpty(system.operator)) {
    fail('every pool system requires systemId and operator');
  }
  if (system.kind !== 'system_under_test' && system.independentOfNewsClawDiscovery !== true) {
    fail(`${system.systemId} must attest independence from NewsClaw discovery`);
  }
}
if (production) {
  const minimum = Number(campaign.requiredCoverage?.minimumAdjudicatedOutputs || 100);
  if (pool.cases.length < minimum) fail(`production pool has ${pool.cases.length} cases; minimum=${minimum}`);
}
if (fs.existsSync(outputPath)) fail(`refusing to overwrite output directory: ${outputPath}`);

const caseIds = new Set();
const itemIds = new Set();
const forbiddenNames = new Set(campaign.dataContracts?.forbiddenBlindPropertyNames || []);
if (forbiddenNames.size === 0) fail('campaign has no forbiddenBlindPropertyNames');
const coordinatorSecrets = new Set(pool.systems.map(item => item.systemId));

function blindedId(kind, value) {
  return `${kind}-${sha256(`${poolHash}:${kind}:${value}`).slice(0, 20)}`;
}

function validateAndBlindCase(item) {
  assertExactKeys(item, new Set(['caseId', 'caseKind', 'reviewMaterial', 'coordinatorOnly']), 'case');
  if (!nonEmpty(item.caseId) || caseIds.has(item.caseId)) fail(`duplicate or empty caseId: ${item.caseId}`);
  caseIds.add(item.caseId);
  if (!['source_observation', 'system_output'].includes(item.caseKind)) {
    fail(`${item.caseId}: invalid caseKind`);
  }
  assertExactKeys(item.reviewMaterial, REVIEW_KEYS, `${item.caseId}.reviewMaterial`);
  const material = item.reviewMaterial;
  for (const key of ['itemId', 'publisher', 'sourceFamily', 'language', 'title']) {
    if (!nonEmpty(material[key])) fail(`${item.caseId}: reviewMaterial.${key} is required`);
  }
  if (itemIds.has(material.itemId)) fail(`duplicate itemId: ${material.itemId}`);
  itemIds.add(material.itemId);
  if (!utc(material.observedAt) || (material.publishedAt !== null && !utc(material.publishedAt))) {
    fail(`${item.caseId}: observation/publication time must be null or an explicit UTC instant`);
  }
  if (!httpUrl(material.url) || !httpUrl(material.canonicalUrl)) {
    fail(`${item.caseId}: source URLs must be HTTP(S)`);
  }
  if (!Array.isArray(material.claims) || !Array.isArray(material.quotes)) {
    fail(`${item.caseId}: claims and quotes must be arrays`);
  }
  assertExactKeys(item.coordinatorOnly,
      new Set(['poolDiscoveries', 'systemUnderTestPrediction']), `${item.caseId}.coordinatorOnly`);
  if (!Array.isArray(item.coordinatorOnly.poolDiscoveries)
      || item.coordinatorOnly.poolDiscoveries.length === 0) {
    fail(`${item.caseId}: poolDiscoveries must not be empty`);
  }

  coordinatorSecrets.add(item.caseId);
  coordinatorSecrets.add(material.itemId);
  for (const discovery of item.coordinatorOnly.poolDiscoveries) {
    if (nonEmpty(discovery.systemId)) coordinatorSecrets.add(discovery.systemId);
    if (nonEmpty(discovery.runId)) coordinatorSecrets.add(discovery.runId);
  }
  const prediction = item.coordinatorOnly.systemUnderTestPrediction;
  if (prediction) {
    for (const value of [prediction.systemEventId, prediction.predictedClusterId]) {
      if (nonEmpty(value)) coordinatorSecrets.add(value);
    }
    for (const value of prediction.rankingSnapshotIds || []) {
      if (nonEmpty(value)) coordinatorSecrets.add(value);
    }
  }
  const blindCaseId = blindedId('case', item.caseId);
  const blindItemId = blindedId('item', material.itemId);
  const claimIdMapping = {};
  const quoteIdMapping = {};
  const claimIds = new Set();
  const quoteIds = new Set();
  const claims = material.claims.map(claim => {
    assertExactKeys(claim, new Set(['claimId', 'text']), `${item.caseId}.claim`);
    if (!nonEmpty(claim.claimId) || !nonEmpty(claim.text) || claimIds.has(claim.claimId)) {
      fail(`${item.caseId}: claim IDs and text must be unique and non-empty`);
    }
    claimIds.add(claim.claimId);
    coordinatorSecrets.add(claim.claimId);
    const blindClaimId = blindedId('claim', `${item.caseId}:${claim.claimId}`);
    claimIdMapping[blindClaimId] = claim.claimId;
    return {claimId: blindClaimId, text: claim.text};
  });
  const quotes = material.quotes.map(quote => {
    assertExactKeys(quote, new Set(['quoteId', 'sourceUrl', 'text']), `${item.caseId}.quote`);
    if (!nonEmpty(quote.quoteId) || !nonEmpty(quote.text) || !httpUrl(quote.sourceUrl)
        || quoteIds.has(quote.quoteId)) {
      fail(`${item.caseId}: quote IDs must be unique and quote URL/text must be valid`);
    }
    quoteIds.add(quote.quoteId);
    coordinatorSecrets.add(quote.quoteId);
    const blindQuoteId = blindedId('quote', `${item.caseId}:${quote.quoteId}`);
    quoteIdMapping[blindQuoteId] = quote.quoteId;
    return {quoteId: blindQuoteId, sourceUrl: quote.sourceUrl, text: quote.text};
  });
  return {
    blindCase: {
      caseId: blindCaseId,
      caseKind: item.caseKind,
      reviewMaterial: {
        itemId: blindItemId,
        observedAt: material.observedAt,
        publisher: material.publisher,
        sourceFamily: material.sourceFamily,
        language: material.language,
        url: material.url,
        canonicalUrl: material.canonicalUrl,
        title: material.title,
        publishedAt: material.publishedAt,
        publishedAtRaw: material.publishedAtRaw == null ? null : String(material.publishedAtRaw),
        mainContent: material.mainContent == null ? null : String(material.mainContent),
        contentSha256: material.contentSha256 == null ? null : String(material.contentSha256),
        claims,
        quotes
      },
      annotation: {
        relevance: null,
        newsType: null,
        reviewerEventId: null,
        novelty: null,
        firstPublishedAt: null,
        titleCorrect: null,
        mainContentQuality: null,
        sourceTier: null,
        claimQuoteRelations: [],
        issueCodes: [],
        notes: ''
      }
    },
    mapping: {
      blindCaseId,
      coordinatorCaseId: item.caseId,
      blindItemId,
      coordinatorItemId: material.itemId,
      claimIdMapping,
      quoteIdMapping
    }
  };
}

const converted = pool.cases.map(validateAndBlindCase);
const baseCases = converted.map(item => item.blindCase);
function reviewerWorksheet(slot) {
  const cases = [...baseCases].sort((left, right) =>
    sha256(`${poolHash}:${slot}:${left.caseId}`)
      .localeCompare(sha256(`${poolHash}:${slot}:${right.caseId}`)));
  return {
    schemaVersion: '1.0',
    worksheetType: 'blind-independent-domain-gold-review',
    worksheetStatus: 'DRAFT',
    campaignId: campaign.campaignId,
    sourcePoolSha256: poolHash,
    reviewer: {
      slot,
      reviewerId: '',
      startedAt: null,
      completedAt: null,
      attestsNoSystemPredictionExposure: false,
      attestsNoOtherReviewerExposure: false,
      attestsIndependentHumanWork: false
    },
    cases
  };
}

const worksheets = new Map([
  ['reviewer-a', reviewerWorksheet('reviewer-a')],
  ['reviewer-b', reviewerWorksheet('reviewer-b')]
]);
for (const [slot, worksheet] of worksheets) {
  const leakedKeys = [...collectPropertyNames(worksheet)].filter(key => forbiddenNames.has(key));
  if (leakedKeys.length) fail(`${slot} leaked forbidden keys: ${leakedKeys.join(',')}`);
  const leakedValues = [...collectStrings(worksheet)].filter(value => coordinatorSecrets.has(value));
  if (leakedValues.length) fail(`${slot} leaked coordinator-only values: ${leakedValues.join(',')}`);
}

fs.mkdirSync(path.join(outputPath, 'coordinator'), {recursive: true});
fs.writeFileSync(path.join(outputPath, 'coordinator', 'source-pool.json'), poolInput.bytes);
for (const [slot, worksheet] of worksheets) {
  writeJson(path.join(outputPath, slot, 'annotations.json'), worksheet);
}
writeJson(path.join(outputPath, 'coordinator', 'adjudication-template.json'), {
  schemaVersion: '1.0',
  campaignId: campaign.campaignId,
  sourcePoolSha256: poolHash,
  reviewerACompletedWorksheetSha256: '',
  reviewerBCompletedWorksheetSha256: '',
  adjudicator: '',
  startedAt: null,
  completedAt: null,
  agreement: {
    exactAgreementByField: null,
    eventIdentityPairwiseAgreement: null,
    semanticRelationCohensKappa: null
  },
  cases: converted.map(item => ({
    ...item.mapping,
    disagreementFields: [],
    finalAnnotation: null,
    adjudicationRationale: '',
    status: 'pending'
  }))
});

const reviewerFiles = {};
for (const slot of worksheets.keys()) {
  const relative = `${slot}/annotations.json`;
  reviewerFiles[slot] = {
    file: relative,
    draftSha256: sha256(fs.readFileSync(path.join(outputPath, relative)))
  };
}
writeJson(path.join(outputPath, 'manifest.json'), {
  schemaVersion: '1.0',
  campaignId: campaign.campaignId,
  datasetClassification: classification,
  evaluationEligible: production,
  campaignManifest: {
    file: path.relative(root, campaignPath),
    sha256: campaignHash
  },
  coordinatorPool: {
    file: path.relative(root, poolPath),
    sha256: poolHash,
    cases: pool.cases.length
  },
  reviewerFiles,
  blindLeakageAudit: {
    passed: true,
    forbiddenPropertyNames: [...forbiddenNames].sort(),
    pseudonymizedCaseItemClaimAndQuoteIds: true,
    independentDeterministicCaseOrder: true
  },
  distributionRule: 'Give each reviewer only that reviewer directory; coordinator files and the other reviewer directory remain sealed.',
  limitations: contractFixture
    ? ['Synthetic contract fixture only; evaluationEligible is false.']
    : []
});

process.stdout.write(`AI_NEWS_DOMAIN_GOLD_PACKAGE=${JSON.stringify({
  output: outputPath,
  classification,
  evaluationEligible: production,
  cases: pool.cases.length,
  sourcePoolSha256: poolHash,
  leakageAuditPassed: true
})}\n`);
