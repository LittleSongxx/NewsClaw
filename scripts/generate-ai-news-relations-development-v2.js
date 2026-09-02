#!/usr/bin/env node
'use strict';

/**
 * Derives the v10 semantic-isolation DEVELOPMENT set from the already visible
 * v1 scaffold. This is deliberately not new holdout evidence: cases and gold
 * relations are unchanged. The only change is a structured primaryClaim field
 * that lets the runner exclude citation/source policy inputs from the model.
 */

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const input = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-relations-development-v1.json');
const output = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-relations-development-v2.json');

const source = JSON.parse(fs.readFileSync(input, 'utf8'));
if (!Array.isArray(source.cases) || source.cases.length !== 30) {
  throw new Error(`expected 30 source cases, got ${source.cases && source.cases.length}`);
}

function primaryClaim(prompt) {
  const firstLine = String(prompt || '').split(/\r?\n/, 1)[0];
  const claim = firstLine.replace(/^[^:：]+[:：]\s*/, '').trim();
  if (!claim || claim === firstLine.trim()) {
    throw new Error(`cannot extract primary claim from: ${firstLine}`);
  }
  return claim;
}

const cases = source.cases.map((sourceCase, index) => {
  const item = structuredClone(sourceCase);
  item.id = `relations-dev-v2-${String(index + 1).padStart(3, '0')}`;
  item.slices.semanticGroup = `RELDEV2-G${String(index + 1).padStart(3, '0')}`;
  item.policyPacket = {
    primaryClaim: primaryClaim(item.prompt),
    ...item.policyPacket
  };
  return item;
});

const dataset = {
  ...source,
  datasetId: 'ai-news-live-agent-evidence-relations-development-v2',
  datasetVersion: '2026-08-26-v2',
  evaluationScope: 'adversarial-development-semantic-relations-isolated-input-plus-deterministic-policy',
  executionMetadata: {
    ...source.executionMetadata,
    promptVersion: 'live-agent-evidence-v10-relations-development',
    semanticModelInput: 'primaryClaim + evidenceId + quote only; URL, publisher, requested citation and gold relation remain outside the model prompt',
    derivedFrom: 'ai-news-live-agent-evidence-relations-development-v1@2026-08-26-v1'
  },
  limitations: [
    ...source.limitations,
    'v2 reuses every visible v1 scenario and label; it measures a protocol correction, not independent generalization.'
  ],
  cases
};

fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(dataset, null, 2)}\n`);
process.stdout.write(`wrote ${cases.length} development cases to ${path.relative(root, output)}\n`);
