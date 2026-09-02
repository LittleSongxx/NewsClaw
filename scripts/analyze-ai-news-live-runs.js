#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

function usage(message) {
  if (message) process.stderr.write(`${message}\n`);
  process.stderr.write(
      'Usage: analyze-ai-news-live-runs.js [--output-json FILE] [--output-markdown FILE] RUN_DIR...\n');
  process.exit(2);
}

const args = process.argv.slice(2);
let outputJson = '';
let outputMarkdown = '';
const inputs = [];
for (let index = 0; index < args.length; index += 1) {
  const arg = args[index];
  if (arg === '--output-json' || arg === '--output-markdown') {
    if (!args[index + 1]) usage(`${arg} requires a file path`);
    if (arg === '--output-json') outputJson = args[index + 1];
    else outputMarkdown = args[index + 1];
    index += 1;
  } else if (arg.startsWith('-')) {
    usage(`unknown option: ${arg}`);
  } else {
    inputs.push(arg);
  }
}
if (inputs.length < 1) usage('at least one run directory is required');

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function oneFile(directory, suffix) {
  const matches = fs.readdirSync(directory).filter(name => name.endsWith(suffix)).sort();
  if (matches.length !== 1) {
    throw new Error(`${directory} must contain exactly one *${suffix}; found ${matches.length}`);
  }
  return path.join(directory, matches[0]);
}

function metric(manifest, name) {
  return manifest.metrics && manifest.metrics[name] ? manifest.metrics[name] : {};
}

function semanticGroup(item) {
  if (item.slices && item.slices.semanticGroup) return item.slices.semanticGroup;
  return item.id.replace(/^holdout-\d+-/, '').replace(/-(zh|en)$/, '');
}

function loadRun(input) {
  const directory = path.resolve(input);
  if (!fs.statSync(directory).isDirectory()) throw new Error(`${input} is not a directory`);
  const qualityFile = oneFile(directory, '.quality-manifest.json');
  const runtimeFile = oneFile(directory, '.runtime-manifest.json');
  const tracesFile = oneFile(directory, '.traces.json');
  const quality = readJson(qualityFile);
  const runtime = readJson(runtimeFile);
  const traces = readJson(tracesFile);
  if (!Array.isArray(traces.cases) || traces.cases.length === 0) {
    throw new Error(`${tracesFile} contains no scored cases`);
  }
  const ids = traces.cases.map(item => item.id);
  if (new Set(ids).size !== ids.length) throw new Error(`${tracesFile} contains duplicate case ids`);
  if (quality.caseCounts.total !== traces.cases.length) {
    throw new Error(`${directory} quality/trace case count mismatch`);
  }
  const metadata = quality.executionMetadata || {};
  const task = metric(quality, 'taskSuccess');
  const cases = traces.cases.map(item => ({
    id: item.id,
    semanticGroup: semanticGroup(item),
    language: item.slices && item.slices.language || 'unknown',
    family: item.slices && item.slices.scenarioFamily || 'unknown',
    passed: item.prediction && item.prediction.taskSucceeded === true
  }));
  return {
    name: path.basename(directory),
    directory,
    datasetId: quality.datasetId,
    datasetVersion: quality.datasetVersion,
    generatedAt: quality.generatedAt,
    gitCommit: quality.gitCommit,
    promptVersion: metadata.promptVersion || 'unknown',
    declaredPromptVersion: metadata.declaredPromptVersion || metadata.promptVersion || 'unknown',
    promptOverride: metadata.promptOverride || 'unknown',
    thinkingLevel: metadata.thinkingLevel || 'model-default',
    modelRoutes: metadata.observedModelRoutes || 'unknown',
    agentId: metadata.agentId || String(runtime.agentId || 'unknown'),
    workspaceId: metadata.workspaceId || String(runtime.workspaceId || 'unknown'),
    evaluationTree: metadata.evaluationTree || 'unknown',
    runClass: metadata.runClass || 'legacy-unclassified',
    caseOrder: metadata.caseOrder || 'dataset',
    formalProtocolSatisfied: metadata.formalProtocolSatisfied || 'false',
    benchmarkSha256: metadata.benchmarkSha256 || 'unknown',
    promptContractSha256: metadata.promptContractSha256 || 'unknown',
    evaluationSourceFingerprint: metadata.evaluationSourceFingerprint || 'unknown',
    serverRevision: metadata.serverRevision || 'unknown',
    total: traces.cases.length,
    taskSuccesses: task.correct == null ? cases.filter(item => item.passed).length : task.correct,
    taskSuccessRate: task.value == null ? null : task.value,
    taskSuccessWilson95: [task.confidenceLower ?? null, task.confidenceUpper ?? null],
    sourceTierAccuracy: metric(quality, 'sourceTier.accuracy').value ?? null,
    claimQuoteAccuracy: metric(quality, 'claimQuoteSupported').value ?? null,
    verificationAccuracy: metric(quality, 'verificationEligible').value ?? null,
    citationBlockAccuracy: metric(quality, 'citationViolationBlocked').value ?? null,
    semanticRelationItemAccuracy: metric(runtime, 'semanticRelationItemAccuracy').value ?? null,
    invalidOutputs: quality.caseCounts.invalidOutputs || 0,
    badcases: quality.badcases ? quality.badcases.length : 0,
    e2eP50Ms: metric(runtime, 'endToEndLatencyMs').p50 ?? null,
    e2eP95Ms: metric(runtime, 'endToEndLatencyMs').p95 ?? null,
    ttfcP50Ms: metric(runtime, 'timeToFirstContentMs').p50 ?? null,
    ttfcP95Ms: metric(runtime, 'timeToFirstContentMs').p95 ?? null,
    cases
  };
}

const runs = inputs.map(loadRun);
const datasetKeys = new Set(runs.map(run => `${run.datasetId}@${run.datasetVersion}`));
if (datasetKeys.size !== 1) {
  throw new Error(`runs use different datasets: ${[...datasetKeys].join(', ')}`);
}
const referenceIds = runs[0].cases.map(item => item.id).sort().join('\n');
for (const run of runs.slice(1)) {
  if (run.cases.map(item => item.id).sort().join('\n') !== referenceIds) {
    throw new Error(`${run.name} does not contain the same frozen case ids as ${runs[0].name}`);
  }
}

function comparisonKey(run) {
  return [run.datasetId, run.datasetVersion, run.promptVersion, run.thinkingLevel, run.modelRoutes,
    run.agentId, run.workspaceId, run.runClass, run.gitCommit, run.benchmarkSha256, run.promptContractSha256,
    run.evaluationSourceFingerprint, run.serverRevision].join('|');
}

const grouped = new Map();
for (const run of runs) {
  const key = comparisonKey(run);
  if (!grouped.has(key)) grouped.set(key, []);
  grouped.get(key).push(run);
}

function repeatAnalysis(groupRuns) {
  const byCase = new Map(groupRuns[0].cases.map(item => [item.id, []]));
  const caseMetadata = new Map(groupRuns[0].cases.map(item => [item.id, item]));
  for (const run of groupRuns) {
    for (const item of run.cases) byCase.get(item.id).push(item.passed);
  }
  const caseRates = [];
  let transitionFlips = 0;
  let transitionComparisons = 0;
  for (const [caseId, values] of byCase) {
    let flips = 0;
    for (let index = 1; index < values.length; index += 1) {
      transitionComparisons += 1;
      if (values[index] !== values[index - 1]) flips += 1;
    }
    transitionFlips += flips;
    const metadata = caseMetadata.get(caseId);
    caseRates.push({
      caseId,
      semanticGroup: metadata.semanticGroup,
      language: metadata.language,
      passes: values.filter(Boolean).length,
      runs: values.length,
      successRate: values.filter(Boolean).length / values.length,
      transitionFlips: flips,
      unstable: values.some(Boolean) && values.some(value => !value)
    });
  }
  const unstable = caseRates.filter(item => item.unstable);
  const bySemanticGroup = new Map();
  for (const item of groupRuns[0].cases) {
    if (!bySemanticGroup.has(item.semanticGroup)) bySemanticGroup.set(item.semanticGroup, new Set());
    bySemanticGroup.get(item.semanticGroup).add(item.id);
  }
  const semanticGroupRates = [];
  for (const [semanticGroup, caseIds] of bySemanticGroup) {
    const runOutcomes = groupRuns.map(run => run.cases
        .filter(item => caseIds.has(item.id)).every(item => item.passed));
    let flips = 0;
    for (let index = 1; index < runOutcomes.length; index += 1) {
      if (runOutcomes[index] !== runOutcomes[index - 1]) flips += 1;
    }
    semanticGroupRates.push({
      semanticGroup,
      cases: caseIds.size,
      successfulRuns: runOutcomes.filter(Boolean).length,
      runs: runOutcomes.length,
      successRate: runOutcomes.filter(Boolean).length / runOutcomes.length,
      transitionFlips: flips,
      unstable: runOutcomes.some(Boolean) && runOutcomes.some(value => !value)
    });
  }
  const successRates = groupRuns.map(run => run.taskSuccesses / run.total);
  const rankedRuns = groupRuns.map((run, index) => ({
    name: run.name, rate: successRates[index], caseOrder: run.caseOrder
  })).sort((left, right) => left.rate - right.rate || left.name.localeCompare(right.name));
  const reproducible = groupRuns.every(run => run.evaluationTree === 'clean'
      && run.benchmarkSha256 !== 'unknown'
      && run.promptContractSha256 !== 'unknown'
      && run.evaluationSourceFingerprint !== 'unknown'
      && run.serverRevision !== 'unknown');
  return {
    signature: comparisonKey(groupRuns[0]),
    promptVersion: groupRuns[0].promptVersion,
    thinkingLevel: groupRuns[0].thinkingLevel,
    modelRoutes: groupRuns[0].modelRoutes,
    runNames: groupRuns.map(run => run.name),
    caseOrders: groupRuns.map(run => run.caseOrder),
    runs: groupRuns.length,
    reproduciblyComparable: reproducible,
    taskSuccessMin: Math.min(...successRates),
    taskSuccessMax: Math.max(...successRates),
    taskSuccessMean: successRates.reduce((sum, value) => sum + value, 0) / successRates.length,
    worstRun: rankedRuns[0],
    bestRun: rankedRuns[rankedRuns.length - 1],
    unstableCaseCount: unstable.length,
    unstableCaseRate: unstable.length / groupRuns[0].total,
    transitionFlips,
    transitionComparisons,
    transitionFlipRate: transitionComparisons === 0 ? null : transitionFlips / transitionComparisons,
    unstableSemanticGroupCount: semanticGroupRates.filter(item => item.unstable).length,
    unstableSemanticGroupRate: semanticGroupRates.filter(item => item.unstable).length
        / semanticGroupRates.length,
    caseRates,
    semanticGroupRates,
    unstableCases: unstable
  };
}

const repeatGroups = [...grouped.values()].filter(group => group.length >= 2).map(repeatAnalysis);
const semanticGroups = new Map();
for (const item of runs[0].cases) {
  if (!semanticGroups.has(item.semanticGroup)) semanticGroups.set(item.semanticGroup, []);
  semanticGroups.get(item.semanticGroup).push(item);
}
const pairedGroups = [...semanticGroups.values()].filter(group => group.length > 1);

const warnings = [];
if (runs.some(run => run.evaluationTree !== 'clean')) {
  warnings.push('One or more runs used a dirty/unknown evaluation tree; Git SHA alone does not identify the evaluated code.');
}
if (runs.some(run => run.serverRevision === 'unknown'
    || run.evaluationSourceFingerprint === 'unknown'
    || run.promptContractSha256 === 'unknown')) {
  warnings.push('Legacy artifacts lack server/source/Prompt fingerprints; repeated scores are descriptive, not reproducibly attributable.');
}
if (new Set(runs.map(run => run.promptVersion)).size > 1) {
  warnings.push('Prompt versions differ; cross-Prompt score changes are development comparisons, not repeatability estimates.');
}
if (semanticGroups.size < runs[0].total) {
  warnings.push(`${runs[0].total} cases collapse to ${semanticGroups.size} semantic groups (${pairedGroups.length} multilingual/variant clusters); case-level Wilson intervals assume more independence than the authored set provides.`);
}
if (runs.some(run => run.promptOverride === 'true')) {
  warnings.push('At least one run overrode the dataset-declared Prompt, so the reused dataset is development evidence.');
}
if (repeatGroups.length > 0 && repeatGroups.some(group => group.runs < 3)) {
  warnings.push('At least one comparison signature has fewer than three repeats; it is insufficient for the predeclared stability protocol.');
}
if (repeatGroups.length > 0) {
  warnings.push('Repeated observations reuse the same cases and are neither independent samples nor a new unseen-holdout result.');
}

const result = {
  schemaVersion: '1.1',
  generatedAt: new Date().toISOString(),
  dataset: [...datasetKeys][0],
  caseCoverage: {
    cases: runs[0].total,
    semanticGroups: semanticGroups.size,
    clusteredGroups: pairedGroups.length
  },
  runs: runs.map(({cases, directory, ...summary}) => summary),
  repeatGroups,
  warnings
};

function number(value) {
  return value == null ? 'n/a' : Number(value).toFixed(4);
}

function markdown(report) {
  const lines = [
    '# AI News Live Evaluation Repeat Analysis', '',
    `- Dataset: \`${report.dataset}\``,
    `- Runs: \`${report.runs.length}\``,
    `- Cases / semantic groups: \`${report.caseCoverage.cases} / ${report.caseCoverage.semanticGroups}\``, '',
    '## Runs', '',
    '| Run | Order | Prompt | Tree | Formal | Task success | Relations | Source | Quote | Verify | E2E P50/P95 ms | TTFC P50/P95 ms |',
    '| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |'
  ];
  for (const run of report.runs) {
    lines.push(`| \`${run.name}\` | \`${run.caseOrder}\` | \`${run.promptVersion}\` | \`${run.evaluationTree}\` | ${run.formalProtocolSatisfied} | ${run.taskSuccesses}/${run.total} (${number(run.taskSuccessRate)}) | ${number(run.semanticRelationItemAccuracy)} | ${number(run.sourceTierAccuracy)} | ${number(run.claimQuoteAccuracy)} | ${number(run.verificationAccuracy)} | ${run.e2eP50Ms ?? 'n/a'} / ${run.e2eP95Ms ?? 'n/a'} | ${run.ttfcP50Ms ?? 'n/a'} / ${run.ttfcP95Ms ?? 'n/a'} |`);
  }
  lines.push('', '## Repeatability', '');
  if (report.repeatGroups.length === 0) {
    lines.push('No two runs have the same complete comparison signature.');
  } else {
    lines.push('| Prompt | Runs / orders | Reproducible provenance | Min / mean / max | Case flips | Unstable semantic groups |',
        '| --- | --- | --- | ---: | ---: | ---: |');
    for (const group of report.repeatGroups) {
      lines.push(`| \`${group.promptVersion}\` | ${group.runs} / ${group.caseOrders.map(value => `\`${value}\``).join(', ')} | ${group.reproduciblyComparable} | ${number(group.taskSuccessMin)} / ${number(group.taskSuccessMean)} / ${number(group.taskSuccessMax)} | ${group.transitionFlips}/${group.transitionComparisons} (${number(group.transitionFlipRate)}); unstable ${group.unstableCaseCount}/${report.caseCoverage.cases} | ${group.unstableSemanticGroupCount}/${report.caseCoverage.semanticGroups} |`);
    }
  }
  lines.push('', '## Warnings', '');
  if (report.warnings.length === 0) lines.push('- None.');
  else report.warnings.forEach(warning => lines.push(`- ${warning}`));
  lines.push('');
  return `${lines.join('\n')}\n`;
}

const markdownOutput = markdown(result);
function write(target, content) {
  if (!target) return;
  const absolute = path.resolve(target);
  fs.mkdirSync(path.dirname(absolute), {recursive: true});
  fs.writeFileSync(absolute, content, 'utf8');
}
write(outputJson, `${JSON.stringify(result, null, 2)}\n`);
write(outputMarkdown, markdownOutput);
if (!outputJson && !outputMarkdown) process.stdout.write(markdownOutput);
else {
  if (outputJson) process.stdout.write(`AI_NEWS_REPEAT_ANALYSIS_JSON=${path.resolve(outputJson)}\n`);
  if (outputMarkdown) process.stdout.write(`AI_NEWS_REPEAT_ANALYSIS_MARKDOWN=${path.resolve(outputMarkdown)}\n`);
}
