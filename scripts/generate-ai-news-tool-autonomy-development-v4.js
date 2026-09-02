#!/usr/bin/env node
'use strict';

/**
 * Creates a protocol-only overlay over the human-confirmed v2 labels. Case
 * prompts, ids, scorer labels, and policy packets remain byte-for-byte equal;
 * only execution metadata narrows the provider-visible active tool schemas.
 */

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const input = path.join(root,
  'newsclaw-server/src/test/resources/evals/ai-news/live-agent-tool-autonomy-development-v2.json');
const output = path.join(root,
  'newsclaw-server/src/test/resources/evals/ai-news/live-agent-tool-autonomy-development-v4.json');

const source = JSON.parse(fs.readFileSync(input, 'utf8'));
if (!Array.isArray(source.cases) || source.cases.length !== 30) {
  throw new Error(`expected 30 v2 cases, got ${source.cases && source.cases.length}`);
}

const dataset = {
  ...source,
  datasetId: 'ai-news-live-agent-tool-autonomy-development-v4',
  datasetVersion: '2026-08-27-v4',
  evaluationScope: 'development-autonomous-read-only-tool-selection-candidate-scoped',
  executionMetadata: {
    ...source.executionMetadata,
    toolCandidates: 'ai_news_event',
    toolCandidatePolicy: 'request-scoped-intersection-over-active-tools',
    labelProvenance: 'v2 scorer decisions confirmed by two human reviewers; AI assisted transcription; inherited unchanged in this protocol-only overlay',
    labelReviewStatus: 'two-human-decisions-confirmed-ai-assisted-transcription',
    protocolOverlayOnly: 'all cases, prompts, gold labels and policy packets are unchanged from v2; only toolCandidates execution metadata is added',
    derivedFrom: 'ai-news-live-agent-tool-autonomy-development-v2@2026-08-26-v2'
  },
  limitations: [
    'This visible development set is not unseen holdout evidence and must not be reported as a new holdout score.',
    'The required operation is one safe source-health read; this set does not measure open-web relevance or production side effects.',
    'Invalid-parameter recovery, timeout recovery, and open-web adapter behavior remain separate deterministic integration tests.',
    'Two human reviewers confirmed the label decisions and AI assisted only with transcription; blind independence and sealed non-exposure are not claimed.',
    'v4 reuses every v2 case and scorer label; differences from v2 measure the request-scoped tool-candidate protocol, not new data.'
  ]
};

fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(dataset, null, 2)}\n`);
process.stdout.write(`wrote ${dataset.cases.length} protocol-overlay cases to ${path.relative(root, output)}\n`);
