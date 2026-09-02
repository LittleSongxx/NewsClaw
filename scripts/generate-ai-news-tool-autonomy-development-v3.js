#!/usr/bin/env node
'use strict';

/**
 * Tightens the already visible autonomy-v2 operational intent after one model
 * skipped an explicitly requested runtime check because it did not affect the
 * separate semantic judgment. Expected tool names/arguments remain scorer-only.
 */

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const input = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-tool-autonomy-development-v2.json');
const output = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-tool-autonomy-development-v3.json');

const source = JSON.parse(fs.readFileSync(input, 'utf8'));
if (!Array.isArray(source.cases) || source.cases.length !== 30) {
  throw new Error(`expected 30 source cases, got ${source.cases && source.cases.length}`);
}

const cases = source.cases.map((sourceCase, index) => {
  const item = structuredClone(sourceCase);
  const language = item.slices.language;
  const required = item.toolExpectation.mode === 'required';
  const operationalRequest = required
      ? (language === 'zh'
        ? '在完成证据关系判断前，必须先实际执行一次当前部署的结构化新闻来源健康检查。这是用户明确要求的独立只读运行步骤；即使检查结果不影响语义关系，也不得跳过。请自行选择合适工具与合法参数，不要从证据包猜测运行状态。'
        : 'Before assessing the evidence relations, you must actually perform one health inspection of the configured structured news sources. This is an explicit, independent, read-only runtime step; do not skip it merely because its result does not change the semantic relation. Choose the appropriate tool and valid arguments yourself, and do not infer runtime status from the packet.')
      : (language === 'zh'
        ? '只判断下面证据关系；用户没有要求部署状态、来源检索或任何外部检查。'
        : 'Assess only the supplied evidence relations. The user did not request deployment status, source lookup, or any external inspection.');
  item.id = `tool-autonomy-dev-v3-${String(index + 1).padStart(3, '0')}`;
  item.slices.semanticGroup = `TOOLAUTO3-G${String(index + 1).padStart(3, '0')}`;
  item.policyPacket.operationalRequest = operationalRequest;
  return item;
});

const dataset = {
  ...source,
  datasetId: 'ai-news-live-agent-tool-autonomy-development-v3',
  datasetVersion: '2026-08-26-v3',
  evaluationScope: 'development-autonomous-read-only-tool-selection-with-explicit-operational-intent',
  executionMetadata: {
    ...source.executionMetadata,
    derivedFrom: 'ai-news-live-agent-tool-autonomy-development-v2@2026-08-26-v2',
    intentTreatment: 'required cases explicitly require the independent runtime action even when semantic labels are unaffected; tool identity and arguments remain scorer-only'
  },
  limitations: [
    ...source.limitations,
    'v3 is tuned from the observed v2 skipped-action badcase and is therefore development-only.'
  ],
  cases
};

fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(dataset, null, 2)}\n`);
process.stdout.write(`wrote ${cases.length} development cases to ${path.relative(root, output)}\n`);
