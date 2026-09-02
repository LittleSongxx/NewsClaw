#!/usr/bin/env node
'use strict';

const assert = require('assert');
const childProcess = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const root = path.resolve(__dirname, '..');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'candidate-review-test-'));
const input = path.join(temp, 'input');
const output = path.join(temp, 'output');
fs.mkdirSync(input);

function write(name, value) {
  fs.writeFileSync(path.join(input, name), `${JSON.stringify(value)}\n`);
}

const candidate = (id, providerId, selectionStatus) => ({
  id: String(id), providerId, selectionStatus,
  title: `title-${id}`, snippet: `snippet-${id}`,
  canonicalUrl: `https://example.com/${id}`,
  publishedAtHint: null, selectionScore: 1, selectionReason: 'hidden',
  queryLane: 'hidden', storyId: null
});
const rows = [candidate(1, 'alpha', 'SELECTED')];
for (let id = 2; id <= 9; id++) rows.push(candidate(id, id < 6 ? 'alpha' : 'beta', 'NOT_SELECTED'));
write('scan-summary.json', {data: {run: {
  id: 'scan-1', discoveryRunId: 'discovery-1', runStatus: 'COMPLETED',
  uniqueCandidateCount: rows.length, selectedCandidateCount: 1
}, providers: [{providerId: 'alpha'}, {providerId: 'beta'}]}});
write('discovery-run.json', {data: {snapshot: {
  windowStart: '2026-08-27T16:00:00Z', windowEnd: '2026-08-28T16:00:00Z',
  snapshotHash: 'a'.repeat(64), rankingHash: 'b'.repeat(64),
  rankingPolicyVersion: 'discovery-temporal-story-v6@test'
}}});
write('candidates-page-1.json', {data: {records: rows}});
write('marginal-alpha-page-1.json', {data: {records: rows.filter(value => value.providerId === 'alpha')}});
write('marginal-beta-page-1.json', {data: {records: rows.filter(value => value.providerId === 'beta')}});

const result = childProcess.spawnSync(process.execPath, [
  path.join(root, 'scripts', 'prepare-ai-news-candidate-shadow-review.js'), input, output
], {encoding: 'utf8'});
assert.strictEqual(result.status, 0, result.stderr || result.stdout);

const worksheet = JSON.parse(fs.readFileSync(path.join(output, 'reviewer', 'annotations.json')));
const mapping = JSON.parse(fs.readFileSync(path.join(output, 'coordinator', 'mapping.json')));
const manifest = JSON.parse(fs.readFileSync(path.join(output, 'manifest.json')));
assert.strictEqual(worksheet.cases.length, 9);
assert.strictEqual(mapping.cases.length, 9);
assert.strictEqual(manifest.sampling.selectedCensus, 1);
assert.strictEqual(manifest.blindLeakageAudit.passed, true);
const reviewerText = JSON.stringify(worksheet);
for (const forbidden of ['providerId', 'selectionStatus', 'scan-1', 'discovery-1', 'alpha', 'beta']) {
  assert.ok(!reviewerText.includes(forbidden), `reviewer worksheet leaked ${forbidden}`);
}
fs.rmSync(temp, {recursive: true, force: true});
process.stdout.write('candidate shadow review package self-check: OK\n');
