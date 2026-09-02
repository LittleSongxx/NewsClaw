#!/usr/bin/env node
'use strict';

/**
 * Builds a DEVELOPMENT-only autonomous tool-choice set from the semantic
 * relations scaffold. The evidence packets are deliberately reused so this
 * file can never be mistaken for a new holdout; only the operational request
 * and tool-choice treatment are under evaluation.
 */

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const input = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-relations-development-v1.json');
const output = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-tool-autonomy-development-v1.json');

const source = JSON.parse(fs.readFileSync(input, 'utf8'));
if (!Array.isArray(source.cases) || source.cases.length !== 30) {
  throw new Error(`expected 30 source cases, got ${source.cases && source.cases.length}`);
}

const requiredIndexes = new Set();
for (let index = 0; index < source.cases.length; index += 1) {
  if (index < 29 && (index % 4 === 0 || index % 4 === 1)) requiredIndexes.add(index);
}
if (requiredIndexes.size !== 15) throw new Error(`expected 15 required cases, got ${requiredIndexes.size}`);

const cases = source.cases.map((sourceCase, index) => {
  const item = structuredClone(sourceCase);
  const required = requiredIndexes.has(index);
  const language = item.slices.language;
  const operationalRequest = required
      ? (language === 'zh'
        ? '运行请求：在完成证据关系判断前，先检查当前部署中结构化新闻来源是否健康。请自行选择合适的只读工具与合法参数，不要从证据包猜测运行状态。'
        : 'Operational request: before assessing the evidence relations, inspect whether the configured structured news sources are healthy. Choose the appropriate read-only tool and valid arguments yourself; do not infer runtime status from the packet.')
      : (language === 'zh'
        ? '运行请求：只判断下面证据包，不要求部署状态、来源检索或外部信息。'
        : 'Operational request: assess only the supplied evidence packet; no deployment status, source lookup, or external information is requested.');
  item.id = `tool-autonomy-dev-v1-${String(index + 1).padStart(3, '0')}`;
  item.prompt = `${operationalRequest}\n${item.prompt}`;
  item.slices = {
    ...item.slices,
    semanticGroup: `TOOLAUTO-G${String(index + 1).padStart(3, '0')}`,
    route: required ? 'autonomous-read-only-tool' : 'autonomous-no-tool'
  };
  item.toolExpectation = required
      ? {
        mode: 'required',
        toolName: 'ai_news_event',
        arguments: {action: 'source_health'},
        selectionMode: 'autonomous'
      }
      : {mode: 'forbidden', selectionMode: 'autonomous'};
  item.gold = {...item.gold, toolSelectionCorrect: true};
  if (required) item.gold.toolParametersCorrect = true;
  else delete item.gold.toolParametersCorrect;
  return item;
});

const dataset = {
  datasetId: 'ai-news-live-agent-tool-autonomy-development-v1',
  datasetVersion: '2026-08-26-v1',
  evaluationScope: 'development-autonomous-read-only-tool-selection-and-parameters',
  executionMetadata: {
    promptVersion: 'live-agent-evidence-v9-relations-development',
    responseSchema: 'ai_news_evidence_relations_v2',
    datasetClass: 'development-not-holdout',
    toolChoicePolicy: 'auto-autonomous-selection',
    toolChoiceEvidenceBoundary: 'toolChoice=auto for every case; expected tool names and arguments are scorer-only fields and are omitted from the runner instruction',
    predeclaredCaseOrders: 'dataset,reverse,rotate-10,rotate-20',
    labelProvenance: 'synthetic development labels derived from the relations development scaffold; not independently human reviewed',
    labelReviewStatus: 'pending-two-independent-reviewers'
  },
  limitations: [
    'This is a development tool-choice set built from an already visible semantic scaffold, never unseen holdout evidence.',
    'The required operation is a safe source-health read; this set does not measure open-web relevance or production side effects.',
    'Invalid-parameter recovery, timeout recovery, and open-web adapter behavior are covered by separate deterministic integration tests.',
    'A genuine pair of independent human reviewers has not signed off these labels.'
  ],
  cases
};

fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(dataset, null, 2)}\n`);
process.stdout.write(`wrote ${cases.length} development cases to ${path.relative(root, output)}\n`);
