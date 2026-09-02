#!/usr/bin/env node
'use strict';

/**
 * Derives a v10 semantic-isolation variant of the already visible autonomous
 * tool DEVELOPMENT set. Tool labels, scenarios, and relation gold are reused;
 * the structured operational request is model-visible while expected tool
 * name/arguments remain scorer-only.
 */

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const input = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-tool-autonomy-development-v1.json');
const output = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-tool-autonomy-development-v2.json');

const source = JSON.parse(fs.readFileSync(input, 'utf8'));
if (!Array.isArray(source.cases) || source.cases.length !== 30) {
  throw new Error(`expected 30 source cases, got ${source.cases && source.cases.length}`);
}

function stripLabel(line) {
  const value = String(line || '').replace(/^[^:：]+[:：]\s*/, '').trim();
  if (!value || value === String(line || '').trim()) {
    throw new Error(`cannot parse labeled line: ${line}`);
  }
  return value;
}

const cases = source.cases.map((sourceCase, index) => {
  const item = structuredClone(sourceCase);
  const lines = String(item.prompt || '').split(/\r?\n/);
  const claimLine = lines.find(line => /^(主声明|Primary claim)/.test(line));
  item.id = `tool-autonomy-dev-v2-${String(index + 1).padStart(3, '0')}`;
  item.slices.semanticGroup = `TOOLAUTO2-G${String(index + 1).padStart(3, '0')}`;
  item.policyPacket = {
    primaryClaim: stripLabel(claimLine),
    operationalRequest: stripLabel(lines[0]),
    ...item.policyPacket
  };
  return item;
});

const dataset = {
  ...source,
  datasetId: 'ai-news-live-agent-tool-autonomy-development-v2',
  datasetVersion: '2026-08-26-v2',
  evaluationScope: 'development-autonomous-read-only-tool-selection-with-isolated-semantic-input',
  executionMetadata: {
    ...source.executionMetadata,
    promptVersion: 'live-agent-evidence-v10-relations-development',
    semanticModelInput: 'operationalRequest + primaryClaim + evidenceId + quote only; scorer tool labels, URL, publisher, citation request and gold relation are excluded',
    derivedFrom: 'ai-news-live-agent-tool-autonomy-development-v1@2026-08-26-v1'
  },
  limitations: [
    ...source.limitations,
    'v2 reuses every visible v1 scenario and scorer label; it is a protocol-isolation check, not independent data.'
  ],
  cases
};

fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(dataset, null, 2)}\n`);
process.stdout.write(`wrote ${cases.length} development cases to ${path.relative(root, output)}\n`);
