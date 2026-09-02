#!/usr/bin/env node
'use strict';

const assert = require('assert');
const childProcess = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const root = path.resolve(__dirname, '..');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'candidate-finalizer-test-'));
const dayRoot = path.join(temp, 'day1');
const exportDir = path.join(dayRoot, 'export');
const packageDir = path.join(dayRoot, 'review-package');
const submissionDir = path.join(temp, 'submission');
const outputDir = path.join(temp, 'finalized');
const prepareScript = path.join(root, 'scripts', 'prepare-ai-news-candidate-shadow-review.js');
const finalizerScript = path.join(root, 'scripts', 'finalize-ai-news-candidate-shadow-review.js');

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function fileHash(file) {
  return sha256(fs.readFileSync(file));
}

function run(script, args) {
  return childProcess.spawnSync(process.execPath, [script, ...args], {cwd: root, encoding: 'utf8'});
}

try {
  fs.mkdirSync(exportDir, {recursive: true});
  const candidate = (id, selectionStatus) => ({
    id: String(id),
    providerId: id % 2 ? 'alpha' : 'beta',
    queryLane: 'lane-1',
    selectionStatus,
    selectionScore: selectionStatus === 'SELECTED' ? 1 : null,
    selectionReason: selectionStatus === 'SELECTED' ? 'current_official' : 'LEGACY_ADMISSION_OR_BUDGET',
    storyId: null,
    sourceClass: 'OFFICIAL',
    title: `candidate-${id}`,
    snippet: `snippet-${id}`,
    canonicalUrl: `https://example.com/candidate-${id}`,
    originalUrl: `https://example.com/candidate-${id}`,
    publishedAtHint: null
  });
  const candidates = Array.from({length: 185}, (_value, index) =>
    candidate(index + 1, index < 10 ? 'SELECTED' : 'NOT_SELECTED'));
  writeJson(path.join(exportDir, 'scan-summary.json'), {data: {
    run: {
      id: 'scan-1',
      discoveryRunId: 'discovery-1',
      runStatus: 'COMPLETED',
      uniqueCandidateCount: candidates.length,
      selectedCandidateCount: 10
    },
    providers: [{providerId: 'alpha'}, {providerId: 'beta'}]
  }});
  writeJson(path.join(exportDir, 'discovery-run.json'), {data: {snapshot: {
    discoveryRunId: 'discovery-1',
    windowStart: '2026-08-28T20:00:00Z',
    windowEnd: '2026-08-29T20:00:00Z',
    snapshotHash: 'a'.repeat(64),
    rankingHash: 'b'.repeat(64),
    rankingPolicyVersion: 'discovery-temporal-story-v7@test',
    candidates: candidates.slice(0, 10).map((value, index) => ({
      rank: index + 1,
      url: value.canonicalUrl
    }))
  }}});
  writeJson(path.join(exportDir, 'candidates-page-1.json'), {data: {records: candidates}});
  writeJson(path.join(exportDir, 'marginal-alpha-page-1.json'), {data: {
    records: candidates.filter(value => value.providerId === 'alpha')
  }});
  writeJson(path.join(exportDir, 'marginal-beta-page-1.json'), {data: {
    records: candidates.filter(value => value.providerId === 'beta')
  }});

  const prepared = run(prepareScript, [exportDir, packageDir]);
  assert.strictEqual(prepared.status, 0, prepared.stderr || prepared.stdout);

  const annotations = readJson(path.join(packageDir, 'reviewer', 'annotations.json'));
  annotations.reviewer = {
    reviewerId: 'reviewer-a',
    startedAt: '2026-08-29T20:00:00Z',
    completedAt: '2026-08-29T20:30:00Z',
    attestsNoCoordinatorMappingExposure: true,
    attestsHumanReview: true
  };
  for (const item of annotations.cases) {
    item.annotation = {
      relevance: 'relevant',
      freshness: 'inside_window',
      duplicateGroup: '',
      decision: 'accept',
      issueCodes: [],
      reason: 'Direct source confirms an in-window AI event.',
      notes: ''
    };
  }
  writeJson(path.join(submissionDir, 'annotations.json'), annotations);

  const referenceEvents = readJson(path.join(packageDir, 'coordinator', 'reference-events-template.json'));
  referenceEvents.collector = 'collector-b';
  referenceEvents.frozenAt = '2026-08-29T20:31:00Z';
  referenceEvents.events = [{
    eventId: 'gold-20260829-001',
    title: 'Example AI releases a model',
    firstPublishedAt: '2026-08-29T12:00:00Z',
    importance: 2,
    slices: {category: 'product', language: 'en', sourceFamily: 'official'}
  }, {
    eventId: 'gold-20260829-002',
    title: 'Example AI releases a second model',
    firstPublishedAt: '2026-08-29T13:00:00Z',
    importance: 1,
    slices: {category: 'product', language: 'en', sourceFamily: 'official'}
  }];
  writeJson(path.join(submissionDir, 'reference-events.json'), referenceEvents);

  const annotationsSha256 = fileHash(path.join(submissionDir, 'annotations.json'));
  const referenceEventsSha256 = fileHash(path.join(submissionDir, 'reference-events.json'));
  writeJson(path.join(submissionDir, 'qc.json'), {
    schemaVersion: 'ai-news-candidate-shadow-qc-v1',
    status: 'COMPLETE',
    originalManifestSha256: fileHash(path.join(packageDir, 'manifest.json')),
    annotationsSha256,
    referenceEventsSha256,
    reviewerTiming: {
      reviewerId: 'reviewer-a',
      recordedStartedAt: '2026-08-29T20:00:00Z',
      recordedCompletedAt: '2026-08-29T20:30:00Z',
      recordedTimestampsMeaning: 'FULL_REVIEW',
      actualReviewWindow: {startAt: '2026-08-29T20:00:00Z', endAt: '2026-08-29T20:30:00Z'},
      explanation: 'The recorded timestamps cover the full review.',
      attestsAccurate: true,
      attestedAt: '2026-08-29T20:40:00Z'
    },
    referenceAudit: {
      collector: {
        collectorId: 'collector-b',
        implementation: 'manual direct-source patrol',
        operator: 'collector-b',
        attestsIndependentOfNewsClawDiscovery: true,
        attestsNoCandidateExposureBeforeFreeze: true,
        attestsNoMappingExposureBeforeFreeze: true,
        attestsPatrolCompleteThroughCutoff: true,
        attestsAllKnownSourcesRecorded: true,
        attestedAt: '2026-08-29T20:35:00Z',
        explanation: 'Collected independently from the frozen source universe.'
      },
      window: referenceEvents.window,
      collectionStartedAt: '2026-08-28T19:55:00Z',
      collectionCompletedAt: '2026-08-29T20:30:00Z',
      coverageCutoffAt: '2026-08-29T20:00:00Z',
      sourceUniverse: {
        frozenAt: '2026-08-28T19:50:00Z',
        includedSources: [{
          sourceId: 'example-ai',
          publisher: 'Example AI',
          endpoint: 'https://example.com/news',
          sourceFamily: 'official',
          languages: ['en'],
          categories: ['product']
        }],
        collectionLog: [{
          kind: 'source_patrol',
          occurredAt: '2026-08-29T12:05:00Z',
          details: 'Checked the Example AI news endpoint.'
        }]
      },
      policies: {
        frozenAt: '2026-08-28T19:50:00Z',
        eventBoundary: {
          version: 'event-boundary-v1',
          rules: ['One atomic product release is one event.']
        },
        importance: {
          version: 'importance-v1',
          levels: {'1': 'routine', '2': 'material', '3': 'industry-wide'}
        },
        slices: {
          version: 'slices-v1',
          categories: [
            'model', 'product', 'open_source', 'research', 'security', 'infrastructure',
            'partnership', 'funding', 'robotics', 'industry', 'policy'
          ],
          languages: ['zh', 'en', 'multilingual'],
          sourceFamilies: ['official', 'media', 'community'],
          languageMeaning: 'language of the first-publication evidence source',
          sourceFamilyMeaning: 'family of the first-publication evidence source'
        }
      },
      events: [{
        eventId: 'gold-20260829-001',
        sources: [{
          sourceId: 'example-ai',
          url: 'https://example.com/news/model-release',
          publisher: 'Example AI',
          sourceFamily: 'official',
          language: 'en',
          publishedAt: '2026-08-29T12:00:00Z',
          rawPublishedAt: '2026-08-29 12:00 UTC',
          publishedAtKind: 'original',
          timePrecision: 'minute'
        }],
        firstPublishedAtEvidenceUrl: 'https://example.com/news/model-release',
        boundary: {
          risk: 'standard',
          decision: 'KEEP',
          relatedEventIds: [],
          reason: 'Atomic product release.',
          adjudicatedBy: 'collector-b',
          adjudicatedAt: '2026-08-29T20:25:00Z',
          secondaryAdjudication: null
        },
        conflictNotes: ''
      }, {
        eventId: 'gold-20260829-002',
        sources: [{
          sourceId: 'example-ai',
          url: 'https://example.com/news/second-model-release',
          publisher: 'Example AI',
          sourceFamily: 'official',
          language: 'en',
          publishedAt: '2026-08-29T13:00:00Z',
          rawPublishedAt: '2026-08-29 13:00 UTC',
          publishedAtKind: 'original',
          timePrecision: 'minute'
        }],
        firstPublishedAtEvidenceUrl: 'https://example.com/news/second-model-release',
        boundary: {
          risk: 'high',
          decision: 'KEEP',
          relatedEventIds: ['gold-20260829-001'],
          reason: 'Separate atomic product release.',
          adjudicatedBy: 'collector-b',
          adjudicatedAt: '2026-08-29T20:25:00Z',
          secondaryAdjudication: {
            decision: 'KEEP',
            reason: 'Independent release evidence confirms the boundary.',
            adjudicatedBy: 'adjudicator-b',
            adjudicatedAt: '2026-08-29T20:27:00Z'
          }
        },
        conflictNotes: ''
      }]
    }
  });

  writeJson(path.join(submissionDir, 'candidate-adjudications.json'), {
    schemaVersion: 'ai-news-candidate-adjudications-v1',
    status: 'COMPLETE',
    snapshotHash: 'a'.repeat(64),
    referenceEventsSha256,
    adjudicator: {
      adjudicatorId: 'coordinator-a',
      startedAt: '2026-08-29T20:40:00Z',
      completedAt: '2026-08-29T20:45:00Z',
      attestsHumanAdjudication: true,
      attestsReferenceSetFrozenBeforeCandidateExposure: true
    },
    candidates: candidates.map(value => ({
      candidateId: value.id,
      status: value.id === '1' || value.id === '2' ? 'MATCHED' : 'NO_MATCH',
      matchedGoldEventId: value.id === '1' ? 'gold-20260829-001'
        : value.id === '2' ? 'gold-20260829-002' : null,
      adjudicationReason: value.id === '1' || value.id === '2'
        ? 'Direct human-confirmed match.' : 'Human-confirmed no match.'
    }))
  });

  const finalized = run(finalizerScript, [dayRoot, submissionDir, outputDir]);
  assert.strictEqual(finalized.status, 0, finalized.stderr || finalized.stdout);
  const dataset = readJson(path.join(outputDir, 'adjudicated-discovery-dataset.json'));
  assert.strictEqual(dataset.discoveryCandidates.length, 185);
  assert.strictEqual(dataset.discoveryCandidates[0].candidateId, '1');
  assert.strictEqual(dataset.discoveryCandidates[0].rank, 1);
  assert.strictEqual(dataset.discoveryCandidates[0].matchedGoldEventId, 'gold-20260829-001');
  assert.strictEqual(dataset.discoveryCandidates[184].candidateId, '185');
  assert.strictEqual(dataset.discoveryCandidates[184].matchedGoldEventId, null);
  assert.strictEqual(dataset.discoveryCandidates[184].adjudicationReason, 'Human-confirmed no match.');
  assert.deepStrictEqual(dataset.config.rankingCutoffs, [5, 10]);
  assert.deepStrictEqual(Object.keys(dataset.goldEvents[0]).sort(),
    ['eventId', 'firstPublishedAt', 'importance', 'slices', 'title']);
  const manifest = readJson(path.join(outputDir, 'submission-manifest.json'));
  assert.strictEqual(manifest.status, 'FINALIZED_OPERATIONAL_SHADOW');
  assert.strictEqual(manifest.evaluationEligible, false);
  assert.strictEqual(manifest.integrity.issuedReviewCases, 30);
  assert.strictEqual(manifest.integrity.adjudicatedCandidates, 185);
  for (const [name, expected] of Object.entries(manifest.outputs)) {
    assert.strictEqual(fileHash(path.join(outputDir, name)), expected, name);
  }
  assert.strictEqual(fs.readFileSync(path.join(outputDir, 'submission-manifest.sha256'), 'utf8'),
    `${fileHash(path.join(outputDir, 'submission-manifest.json'))}  submission-manifest.json\n`);

  const unresolvedSubmission = path.join(temp, 'submission-unresolved');
  fs.cpSync(submissionDir, unresolvedSubmission, {recursive: true});
  const unresolved = readJson(path.join(unresolvedSubmission, 'candidate-adjudications.json'));
  unresolved.candidates[2].status = 'UNRESOLVED';
  unresolved.candidates[2].adjudicationReason = 'Insufficient evidence; kept null for human follow-up.';
  writeJson(path.join(unresolvedSubmission, 'candidate-adjudications.json'), unresolved);
  const unresolvedOutput = path.join(temp, 'unresolved-output');
  const rejectedUnresolved = run(finalizerScript, [dayRoot, unresolvedSubmission, unresolvedOutput]);
  assert.notStrictEqual(rejectedUnresolved.status, 0);
  assert.match(rejectedUnresolved.stderr, /remains UNRESOLVED and cannot be scored safely/);
  assert.ok(!fs.existsSync(unresolvedOutput));

  const materialTampered = path.join(temp, 'submission-material-tampered');
  fs.cpSync(submissionDir, materialTampered, {recursive: true});
  const changed = readJson(path.join(materialTampered, 'annotations.json'));
  changed.cases[0].material.title = 'tampered';
  writeJson(path.join(materialTampered, 'annotations.json'), changed);
  const materialOutput = path.join(temp, 'material-tampered-output');
  const rejectedMaterial = run(finalizerScript, [dayRoot, materialTampered, materialOutput]);
  assert.notStrictEqual(rejectedMaterial.status, 0);
  assert.match(rejectedMaterial.stderr, /review material changed/);
  assert.ok(!fs.existsSync(materialOutput));

  const hashTamperedDay = path.join(temp, 'day1-hash-tampered');
  fs.cpSync(dayRoot, hashTamperedDay, {recursive: true});
  fs.appendFileSync(path.join(hashTamperedDay, 'export', 'candidates-page-1.json'), ' ');
  const hashOutput = path.join(temp, 'hash-tampered-output');
  const rejectedHash = run(finalizerScript, [hashTamperedDay, submissionDir, hashOutput]);
  assert.notStrictEqual(rejectedHash.status, 0);
  assert.match(rejectedHash.stderr, /original export hash mismatch/);
  assert.ok(!fs.existsSync(hashOutput));

  process.stdout.write('candidate shadow finalizer self-check: OK\n');
} finally {
  fs.rmSync(temp, {recursive: true, force: true});
}
