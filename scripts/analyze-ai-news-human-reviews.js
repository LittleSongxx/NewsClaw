#!/usr/bin/env node
'use strict';

/**
 * Compares two completed blind-review directories without treating agreement
 * as proof of independent human work. The report keeps structural integrity,
 * label agreement, legacy-gold agreement, and formal-protocol eligibility as
 * separate dimensions.
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
if (args.length < 2 || args.length > 3) {
  process.stderr.write('Usage: analyze-ai-news-human-reviews.js <reviewer-a-dir> <reviewer-b-dir> [output-dir]\n');
  process.exit(2);
}

const reviewerADir = path.resolve(args[0]);
const reviewerBDir = path.resolve(args[1]);
const outputDir = args[2]
  ? path.resolve(args[2])
  : path.join(root, 'target', 'ai-news-human-review-analysis-20260827');
const policyFields = [
  'sourceTier', 'verificationEligible', 'citationAllowed', 'claimQuoteSupported',
  'refusalRequired', 'unresolvedConflict', 'humanReviewRequired'
];
const relationLabels = new Set(['entails', 'contradicts', 'partial', 'hedged', 'unrelated']);
const riskLabels = new Set(['normal', 'high']);
const sourceTiers = new Set(['official', 'media', 'community']);

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key =>
      `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function loadWorksheets(directory) {
  if (!fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) {
    throw new Error(`review directory not found: ${directory}`);
  }
  const worksheets = new Map();
  const files = fs.readdirSync(directory).filter(name => name.endsWith('.json')).sort();
  for (const name of files) {
    const file = path.join(directory, name);
    let document;
    try {
      document = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
      throw new Error(`invalid JSON ${file}: ${error.message}`);
    }
    if (document.worksheetType !== 'blind-independent-human-review') continue;
    const datasetId = document.sourceDataset && document.sourceDataset.datasetId;
    if (!datasetId) throw new Error(`worksheet lacks source dataset id: ${file}`);
    if (worksheets.has(datasetId)) throw new Error(`duplicate worksheet for ${datasetId}: ${directory}`);
    worksheets.set(datasetId, {file, document});
  }
  if (worksheets.size === 0) throw new Error(`no review worksheets found: ${directory}`);
  return worksheets;
}

function checksumAudit(directory) {
  const checksumFile = path.join(directory, 'SHA256SUMS.txt');
  if (!fs.existsSync(checksumFile)) {
    return {file: checksumFile, valid: false, entries: [], errors: ['SHA256SUMS.txt is missing']};
  }
  const entries = [];
  const errors = [];
  for (const rawLine of fs.readFileSync(checksumFile, 'utf8').split(/\r?\n/)) {
    if (!rawLine.trim()) continue;
    const match = rawLine.match(/^([a-f0-9]{64})\s+\*?(.*)$/i);
    if (!match) {
      errors.push(`malformed checksum line: ${rawLine}`);
      continue;
    }
    const expected = match[1].toLowerCase();
    const relative = match[2];
    const resolved = path.resolve(directory, relative);
    if (resolved !== directory && !resolved.startsWith(`${directory}${path.sep}`)) {
      errors.push(`checksum path escapes review directory: ${relative}`);
      continue;
    }
    if (!fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) {
      entries.push({file: relative, expectedSha256: expected, actualSha256: null, status: 'missing'});
      errors.push(`checksum target is missing: ${relative}`);
      continue;
    }
    const actual = sha256(resolved);
    const status = actual === expected ? 'match' : 'mismatch';
    entries.push({file: relative, expectedSha256: expected, actualSha256: actual, status});
    if (status !== 'match') errors.push(`checksum mismatch: ${relative}`);
  }
  return {file: checksumFile, valid: errors.length === 0, entries, errors};
}

function validateWorksheet(worksheet) {
  const errors = [];
  const warnings = [];
  const document = worksheet.document;
  const reviewer = document.reviewer || {};
  if (!reviewer.reviewerId) errors.push('reviewerId is blank');
  if (!reviewer.startedAt || !reviewer.completedAt) errors.push('review timestamps are incomplete');
  let durationMs = null;
  if (reviewer.startedAt && reviewer.completedAt) {
    durationMs = Date.parse(reviewer.completedAt) - Date.parse(reviewer.startedAt);
    if (!Number.isFinite(durationMs)) errors.push('review timestamps are invalid');
    else if (durationMs <= 0) warnings.push('reported annotation duration is not positive');
  }
  if (!Array.isArray(document.cases) || document.cases.length === 0) {
    errors.push('cases are missing');
    return {errors, warnings, durationMs, cases: 0, relationItems: 0};
  }
  const caseIds = new Set();
  let relationItems = 0;
  for (const item of document.cases) {
    if (!item.caseId || caseIds.has(item.caseId)) errors.push(`duplicate or blank case id: ${item.caseId}`);
    caseIds.add(item.caseId);
    const evidence = item.taskInput && Array.isArray(item.taskInput.evidence)
      ? item.taskInput.evidence : [];
    const evidenceIds = evidence.map(value => String(value.id));
    const annotations = item.annotation && Array.isArray(item.annotation.evidenceRelations)
      ? item.annotation.evidenceRelations : [];
    relationItems += annotations.length;
    if (canonical(evidenceIds.slice().sort()) !== canonical(annotations
        .map(value => String(value.evidenceId)).sort())) {
      errors.push(`${item.caseId}: evidence relation IDs do not exactly match task input`);
    }
    for (const relation of annotations) {
      if (!relationLabels.has(relation.relation)) {
        errors.push(`${item.caseId}/${relation.evidenceId}: invalid relation ${relation.relation}`);
      }
      if (typeof relation.confidence !== 'number' || relation.confidence < 0.5
          || relation.confidence > 1) {
        errors.push(`${item.caseId}/${relation.evidenceId}: confidence must be within [0.5,1]`);
      } else if (relation.confidence < 0.8 && !String(relation.rationale || '').trim()) {
        errors.push(`${item.caseId}/${relation.evidenceId}: confidence below 0.8 requires rationale`);
      }
    }
    const annotation = item.annotation || {};
    if (!riskLabels.has(annotation.riskLevel)) errors.push(`${item.caseId}: invalid riskLevel`);
    const policy = annotation.policyDecision || {};
    if (!sourceTiers.has(policy.sourceTier)) errors.push(`${item.caseId}: invalid sourceTier`);
    for (const field of policyFields.filter(field => field !== 'sourceTier')) {
      if (typeof policy[field] !== 'boolean') errors.push(`${item.caseId}: ${field} must be boolean`);
    }
    if (typeof annotation.caseValid !== 'boolean') errors.push(`${item.caseId}: caseValid must be boolean`);
    if (!Array.isArray(annotation.issueCodes)) errors.push(`${item.caseId}: issueCodes must be an array`);
    const expectsAutonomousTool = Array.isArray(document.annotationScope)
      && document.annotationScope.includes('autonomous-tool-intent');
    if (expectsAutonomousTool) {
      const tool = annotation.autonomousToolExpectation;
      if (!tool || !['required', 'forbidden'].includes(tool.mode)) {
        errors.push(`${item.caseId}: autonomous tool mode must be required or forbidden`);
      } else if (tool.mode === 'required') {
        if (!tool.toolName || !tool.arguments || typeof tool.arguments !== 'object') {
          errors.push(`${item.caseId}: required tool needs name and arguments`);
        }
      } else if (tool.toolName != null || tool.arguments != null) {
        errors.push(`${item.caseId}: forbidden tool must keep name and arguments null`);
      }
    }
  }
  return {errors, warnings, durationMs, cases: document.cases.length, relationItems};
}

function reviewerIdentity(worksheets) {
  const values = [...worksheets.values()].map(value => value.document.reviewer || {});
  const ids = [...new Set(values.map(value => value.reviewerId || ''))];
  const slots = [...new Set(values.map(value => value.slot || ''))];
  return {
    reviewerIds: ids,
    slots,
    organizationOrTeams: [...new Set(values.map(value => value.organizationOrTeam || ''))],
    allAttestNoGoldOrOtherReviewerExposure: values.every(value =>
      value.attestsNoGoldOrOtherReviewerExposure === true),
    allAttestIndependentHumanWork: values.every(value =>
      value.attestsIndependentHumanWork === true),
    startedAt: [...new Set(values.map(value => value.startedAt || ''))],
    completedAt: [...new Set(values.map(value => value.completedAt || ''))]
  };
}

function agreementCounter() {
  return {agree: 0, total: 0, rate: null};
}

function observe(counter, a, b) {
  counter.total += 1;
  if (canonical(a) === canonical(b)) counter.agree += 1;
  counter.rate = counter.total === 0 ? null : counter.agree / counter.total;
}

function cohensKappa(pairs) {
  const n = pairs.length;
  if (n === 0) return {observations: 0, observedAgreement: null, expectedAgreement: null, kappa: null};
  const labels = [...new Set(pairs.flat())].sort();
  const countsA = new Map(labels.map(label => [label, 0]));
  const countsB = new Map(labels.map(label => [label, 0]));
  let equal = 0;
  for (const [a, b] of pairs) {
    countsA.set(a, (countsA.get(a) || 0) + 1);
    countsB.set(b, (countsB.get(b) || 0) + 1);
    if (a === b) equal += 1;
  }
  const observed = equal / n;
  const expected = labels.reduce((sum, label) =>
    sum + (countsA.get(label) / n) * (countsB.get(label) / n), 0);
  const kappa = expected === 1 ? (observed === 1 ? 1 : null)
    : (observed - expected) / (1 - expected);
  return {
    observations: n,
    labels,
    observedAgreement: observed,
    expectedAgreement: expected,
    kappa,
    marginalsA: Object.fromEntries(labels.map(label => [label, countsA.get(label)])),
    marginalsB: Object.fromEntries(labels.map(label => [label, countsB.get(label)]))
  };
}

function compareDataset(aEntry, bEntry) {
  const a = aEntry.document;
  const b = bEntry.document;
  const structuralErrors = [];
  const disagreements = [];
  const fieldAgreement = {
    semanticRelation: agreementCounter(),
    riskLevel: agreementCounter(),
    sourceTier: agreementCounter(),
    verificationEligible: agreementCounter(),
    citationAllowed: agreementCounter(),
    claimQuoteSupported: agreementCounter(),
    refusalRequired: agreementCounter(),
    unresolvedConflict: agreementCounter(),
    humanReviewRequired: agreementCounter(),
    autonomousToolMode: agreementCounter(),
    autonomousToolName: agreementCounter(),
    autonomousToolArguments: agreementCounter(),
    caseValid: agreementCounter()
  };
  if (a.sourceDataset.sha256 !== b.sourceDataset.sha256) {
    structuralErrors.push('source dataset hashes differ between reviewers');
  }
  const casesA = new Map(a.cases.map(item => [item.caseId, item]));
  const casesB = new Map(b.cases.map(item => [item.caseId, item]));
  const allCaseIds = [...new Set([...casesA.keys(), ...casesB.keys()])].sort();
  const relationPairs = [];
  const confidenceDifferences = [];
  for (const caseId of allCaseIds) {
    const itemA = casesA.get(caseId);
    const itemB = casesB.get(caseId);
    if (!itemA || !itemB) {
      structuralErrors.push(`${caseId}: case is missing from one reviewer`);
      continue;
    }
    if (canonical(itemA.taskInput) !== canonical(itemB.taskInput)) {
      structuralErrors.push(`${caseId}: task inputs differ between reviewers`);
    }
    const relationsA = new Map(itemA.annotation.evidenceRelations
      .map(value => [String(value.evidenceId), value]));
    const relationsB = new Map(itemB.annotation.evidenceRelations
      .map(value => [String(value.evidenceId), value]));
    const evidenceIds = [...new Set([...relationsA.keys(), ...relationsB.keys()])].sort();
    for (const evidenceId of evidenceIds) {
      const valueA = relationsA.get(evidenceId);
      const valueB = relationsB.get(evidenceId);
      if (!valueA || !valueB) {
        structuralErrors.push(`${caseId}/${evidenceId}: relation is missing from one reviewer`);
        continue;
      }
      observe(fieldAgreement.semanticRelation, valueA.relation, valueB.relation);
      relationPairs.push([valueA.relation, valueB.relation]);
      confidenceDifferences.push(Math.abs(valueA.confidence - valueB.confidence));
      if (valueA.relation !== valueB.relation) {
        disagreements.push({caseId, evidenceId, field: 'semanticRelation',
          reviewerA: valueA.relation, reviewerB: valueB.relation});
      }
    }
    const scalarPairs = [
      ['riskLevel', itemA.annotation.riskLevel, itemB.annotation.riskLevel],
      ...policyFields.map(field => [field,
        itemA.annotation.policyDecision[field], itemB.annotation.policyDecision[field]]),
      ['caseValid', itemA.annotation.caseValid, itemB.annotation.caseValid]
    ];
    for (const [field, valueA, valueB] of scalarPairs) {
      observe(fieldAgreement[field], valueA, valueB);
      if (canonical(valueA) !== canonical(valueB)) {
        disagreements.push({caseId, field, reviewerA: valueA, reviewerB: valueB});
      }
    }
    const toolA = itemA.annotation.autonomousToolExpectation;
    const toolB = itemB.annotation.autonomousToolExpectation;
    if (toolA != null || toolB != null) {
      const toolPairs = [
        ['autonomousToolMode', toolA && toolA.mode, toolB && toolB.mode],
        ['autonomousToolName', toolA && toolA.toolName, toolB && toolB.toolName],
        ['autonomousToolArguments', toolA && toolA.arguments, toolB && toolB.arguments]
      ];
      for (const [field, valueA, valueB] of toolPairs) {
        observe(fieldAgreement[field], valueA, valueB);
        if (canonical(valueA) !== canonical(valueB)) {
          disagreements.push({caseId, field, reviewerA: valueA, reviewerB: valueB});
        }
      }
    }
  }
  return {
    datasetId: a.sourceDataset.datasetId,
    datasetVersion: a.sourceDataset.datasetVersion,
    sourceDatasetSha256: a.sourceDataset.sha256,
    cases: allCaseIds.length,
    semanticRelationAgreement: cohensKappa(relationPairs),
    confidenceMeanAbsoluteDifference: confidenceDifferences.length === 0 ? null
      : confidenceDifferences.reduce((sum, value) => sum + value, 0) / confidenceDifferences.length,
    fieldAgreement,
    structuralErrors,
    disagreements
  };
}

function compareToLegacy(worksheet) {
  const document = worksheet.document;
  const filename = document.sourceDataset.originalFilename;
  const original = path.join(root, 'newsclaw-server/src/test/resources/evals/ai-news', filename);
  const result = {
    originalFile: original,
    expectedSha256: document.sourceDataset.sha256,
    actualSha256: null,
    sourceHashMatches: false,
    semanticRelation: agreementCounter(),
    riskLevel: agreementCounter(),
    policyFields: Object.fromEntries(policyFields.map(field => [field, agreementCounter()])),
    autonomousToolMode: agreementCounter(),
    autonomousToolName: agreementCounter(),
    autonomousToolArguments: agreementCounter(),
    differences: []
  };
  if (!fs.existsSync(original)) {
    result.differences.push({field: 'sourceDataset', issue: 'original file missing'});
    return result;
  }
  result.actualSha256 = sha256(original);
  result.sourceHashMatches = result.actualSha256 === result.expectedSha256;
  const source = JSON.parse(fs.readFileSync(original, 'utf8'));
  const sourceCases = new Map(source.cases.map(item => [item.id, item]));
  for (const item of document.cases) {
    const sourceCase = sourceCases.get(item.caseId);
    if (!sourceCase) {
      result.differences.push({caseId: item.caseId, field: 'case', issue: 'missing from original'});
      continue;
    }
    const sourceEvidence = new Map(((sourceCase.policyPacket && sourceCase.policyPacket.evidence) || [])
      .map(value => [String(value.id), value]));
    for (const relation of item.annotation.evidenceRelations) {
      const expected = sourceEvidence.get(String(relation.evidenceId));
      if (expected && expected.expectedRelation != null) {
        observe(result.semanticRelation, relation.relation, expected.expectedRelation);
        if (relation.relation !== expected.expectedRelation) {
          result.differences.push({caseId: item.caseId, evidenceId: relation.evidenceId,
            field: 'semanticRelation', reviewed: relation.relation,
            legacy: expected.expectedRelation});
        }
      }
    }
    const legacyRisk = sourceCase.slices && sourceCase.slices.risk === 'high' ? 'high' : 'normal';
    observe(result.riskLevel, item.annotation.riskLevel, legacyRisk);
    if (item.annotation.riskLevel !== legacyRisk) {
      result.differences.push({caseId: item.caseId, field: 'riskLevel',
        reviewed: item.annotation.riskLevel, legacy: legacyRisk});
    }
    for (const field of policyFields) {
      const reviewed = item.annotation.policyDecision[field];
      const legacy = sourceCase.gold && sourceCase.gold[field];
      if (legacy !== undefined) {
        observe(result.policyFields[field], reviewed, legacy);
        if (canonical(reviewed) !== canonical(legacy)) {
          result.differences.push({caseId: item.caseId, field,
            reviewed, legacy});
        }
      }
    }
    if (item.annotation.autonomousToolExpectation != null && sourceCase.toolExpectation) {
      const reviewed = item.annotation.autonomousToolExpectation;
      const legacy = sourceCase.toolExpectation;
      const pairs = [
        ['autonomousToolMode', reviewed.mode, legacy.mode],
        ['autonomousToolName', reviewed.toolName, legacy.toolName == null ? null : legacy.toolName],
        ['autonomousToolArguments', reviewed.arguments,
          legacy.arguments == null ? null : legacy.arguments]
      ];
      for (const [field, reviewedValue, legacyValue] of pairs) {
        observe(result[field], reviewedValue, legacyValue);
        if (canonical(reviewedValue) !== canonical(legacyValue)) {
          result.differences.push({caseId: item.caseId, field,
            reviewed: reviewedValue, legacy: legacyValue});
        }
      }
    }
  }
  return result;
}

function markdown(report) {
  const lines = [];
  lines.push('# AI 新闻双人标注审计报告', '');
  lines.push(`结论：**${report.formalProtocolEligible ? '满足 formal 双人标注准入' : '不满足 formal 双人标注准入'}**。`, '');
  lines.push('标签一致性与评测诚信是两件事。即使 A/B 标签完全一致，只要独立人工声明、盲标声明、文件完整性或仲裁证据不满足，就不能把数据状态改成 `two-independent-reviewers-complete`。', '');
  lines.push('## Reviewer 元数据', '');
  lines.push('| 角色 | Reviewer ID | slot | 未看金标/他人结果 | 独立人工完成 | 开始 | 完成 |',
    '|---|---|---|---:|---:|---|---|');
  for (const [label, identity] of [['A', report.reviewers.a], ['B', report.reviewers.b]]) {
    lines.push(`| ${label} | ${identity.reviewerIds.join(', ')} | ${identity.slots.join(', ')} | ${identity.allAttestNoGoldOrOtherReviewerExposure} | ${identity.allAttestIndependentHumanWork} | ${identity.startedAt.join(', ')} | ${identity.completedAt.join(', ')} |`);
  }
  lines.push('', '## A/B 标签一致性', '');
  lines.push('| 数据集 | case | relation N | relation 一致率 | Cohen’s κ | 分歧 | 结构错误 |',
    '|---|---:|---:|---:|---:|---:|---:|');
  for (const item of report.datasets) {
    const relation = item.semanticRelationAgreement;
    lines.push(`| ${item.datasetId} | ${item.cases} | ${relation.observations} | ${formatRate(relation.observedAgreement)} | ${formatNumber(relation.kappa)} | ${item.disagreements.length} | ${item.structuralErrors.length} |`);
  }
  lines.push('', '逐字段一致率：', '');
  for (const item of report.datasets) {
    const fields = Object.entries(item.fieldAgreement)
      .filter(([, value]) => value.total > 0)
      .map(([key, value]) => `${key}=${value.agree}/${value.total}`)
      .join('，');
    lines.push(`- ${item.datasetId}：${fields}；confidence MAE=${formatNumber(item.confidenceMeanAbsoluteDifference)}`);
  }
  lines.push('', '## 与既有 scorer 标签的对照', '');
  lines.push('| Reviewer | 数据集 | relation | risk | policy 字段 | tool mode | 差异 |',
    '|---|---|---:|---:|---:|---:|---:|');
  for (const [role, datasets] of Object.entries(report.legacyAgreement)) {
    for (const [datasetId, item] of Object.entries(datasets)) {
      const policyAgree = Object.values(item.policyFields)
        .reduce((sum, value) => sum + value.agree, 0);
      const policyTotal = Object.values(item.policyFields)
        .reduce((sum, value) => sum + value.total, 0);
      lines.push(`| ${role.toUpperCase()} | ${datasetId} | ${item.semanticRelation.agree}/${item.semanticRelation.total} | ${item.riskLevel.agree}/${item.riskLevel.total} | ${policyAgree}/${policyTotal} | ${item.autonomousToolMode.agree}/${item.autonomousToolMode.total} | ${item.differences.length} |`);
    }
  }
  lines.push('', '这里的 100% 只表示提交标签与已有 scorer 标签相同，不能当作独立正确率证据。sealed v2 原件没有逐 evidence relation 金标，所以该列为 0/0。');
  lines.push('', '## 文件与 formal 准入', '');
  lines.push(`- Reviewer A checksum 清单：${report.integrity.a.valid ? '通过' : '失败'}。`);
  lines.push(`- Reviewer B checksum 清单：${report.integrity.b.valid ? '通过' : '失败'}。`);
  lines.push(`- 完整性校验错误：A=${report.validation.a.errors.length}，B=${report.validation.b.errors.length}。`);
  lines.push(`- Formal 准入：${report.formalProtocolEligible ? '通过' : '拒绝'}。`);
  if (report.formalBlockers.length > 0) {
    lines.push('', '阻断项：', '');
    for (const blocker of report.formalBlockers) lines.push(`- ${blocker}`);
  }
  const allDisagreements = report.datasets.flatMap(item => item.disagreements
    .map(value => ({datasetId: item.datasetId, ...value})));
  lines.push('', '## 分歧', '');
  if (allDisagreements.length === 0) lines.push('A/B 标签字段没有分歧。');
  else {
    for (const value of allDisagreements) {
      lines.push(`- ${value.datasetId}/${value.caseId}${value.evidenceId ? `/${value.evidenceId}` : ''} ${value.field}: A=${canonical(value.reviewerA)} B=${canonical(value.reviewerB)}`);
    }
  }
  lines.push('', '## 解释边界', '',
    '本报告只描述所提供文件。它不证明标注者身份真实性，也不将 AI-assisted draft 升级为独立人工复核；sealed v2 已经 first-look，任何事后标注都不能恢复 unseen holdout 身份。', '');
  return `${lines.join('\n')}\n`;
}

function formatRate(value) {
  return value == null ? 'n/a' : `${(value * 100).toFixed(2)}%`;
}

function formatNumber(value) {
  return value == null ? 'n/a' : Number(value).toFixed(4);
}

const worksheetsA = loadWorksheets(reviewerADir);
const worksheetsB = loadWorksheets(reviewerBDir);
const datasetIds = [...new Set([...worksheetsA.keys(), ...worksheetsB.keys()])].sort();
const validationA = [...worksheetsA.values()].map(value => ({
  file: value.file,
  ...validateWorksheet(value)
}));
const validationB = [...worksheetsB.values()].map(value => ({
  file: value.file,
  ...validateWorksheet(value)
}));
const datasets = [];
const missingDatasets = [];
for (const datasetId of datasetIds) {
  if (!worksheetsA.has(datasetId) || !worksheetsB.has(datasetId)) {
    missingDatasets.push(datasetId);
    continue;
  }
  datasets.push(compareDataset(worksheetsA.get(datasetId), worksheetsB.get(datasetId)));
}

const identityA = reviewerIdentity(worksheetsA);
const identityB = reviewerIdentity(worksheetsB);
const integrityA = checksumAudit(reviewerADir);
const integrityB = checksumAudit(reviewerBDir);
const legacyAgreement = {
  a: Object.fromEntries([...worksheetsA.entries()].map(([id, value]) => [id, compareToLegacy(value)])),
  b: Object.fromEntries([...worksheetsB.entries()].map(([id, value]) => [id, compareToLegacy(value)]))
};
const validation = {
  a: {
    worksheets: validationA,
    errors: validationA.flatMap(value => value.errors.map(error => `${value.file}: ${error}`)),
    warnings: validationA.flatMap(value => value.warnings.map(warning => `${value.file}: ${warning}`))
  },
  b: {
    worksheets: validationB,
    errors: validationB.flatMap(value => value.errors.map(error => `${value.file}: ${error}`)),
    warnings: validationB.flatMap(value => value.warnings.map(warning => `${value.file}: ${warning}`))
  }
};

const formalBlockers = [];
if (!integrityA.valid) formalBlockers.push('Reviewer A 的 SHA256SUMS 不完整或与当前文件不匹配');
if (!integrityB.valid) formalBlockers.push('Reviewer B 的 SHA256SUMS 不完整或与当前文件不匹配');
if (validation.a.errors.length > 0) formalBlockers.push('Reviewer A 工作表存在结构/完整性错误');
if (validation.b.errors.length > 0) formalBlockers.push('Reviewer B 工作表存在结构/完整性错误');
if (identityA.reviewerIds.length !== 1 || !identityA.reviewerIds[0]) {
  formalBlockers.push('Reviewer A 身份在工作表之间不唯一或为空');
}
if (identityB.reviewerIds.length !== 1 || !identityB.reviewerIds[0]) {
  formalBlockers.push('Reviewer B 身份在工作表之间不唯一或为空');
}
if (identityA.reviewerIds.length === 1 && identityB.reviewerIds.length === 1
    && identityA.reviewerIds[0] === identityB.reviewerIds[0]) {
  formalBlockers.push('Reviewer A/B 必须是不同身份');
}
if (!identityA.allAttestNoGoldOrOtherReviewerExposure) {
  formalBlockers.push('Reviewer A 未声明全程未查看金标或另一位结果');
}
if (!identityB.allAttestNoGoldOrOtherReviewerExposure) {
  formalBlockers.push('Reviewer B 未声明全程未查看金标或另一位结果');
}
if (!identityA.allAttestIndependentHumanWork) {
  formalBlockers.push('Reviewer A 未声明由独立真人完成');
}
if (!identityB.allAttestIndependentHumanWork) {
  formalBlockers.push('Reviewer B 未声明由独立真人完成');
}
if (validationA.some(value => value.durationMs != null && value.durationMs <= 0)) {
  formalBlockers.push('Reviewer A 报告的标注用时不是正数');
}
if (validationB.some(value => value.durationMs != null && value.durationMs <= 0)) {
  formalBlockers.push('Reviewer B 报告的标注用时不是正数');
}
if (missingDatasets.length > 0) formalBlockers.push(`A/B 缺少配对数据集：${missingDatasets.join(', ')}`);
if (datasets.some(item => item.structuralErrors.length > 0)) {
  formalBlockers.push('A/B 的 case/task input/evidence 对齐存在结构错误');
}
if (datasets.some(item => item.disagreements.length > 0)) {
  formalBlockers.push('A/B 标签存在尚未仲裁的分歧');
}
for (const [role, legacy] of Object.entries(legacyAgreement)) {
  for (const [datasetId, value] of Object.entries(legacy)) {
    if (!value.sourceHashMatches) {
      formalBlockers.push(`Reviewer ${role.toUpperCase()} 的 ${datasetId} 源数据哈希与当前原件不一致`);
    }
  }
}

const report = {
  schemaVersion: '1.0',
  evaluationScope: 'human-review-file-integrity-agreement-and-formal-eligibility',
  generatedAt: new Date().toISOString(),
  inputDirectories: {reviewerA: reviewerADir, reviewerB: reviewerBDir},
  reviewers: {a: identityA, b: identityB},
  integrity: {a: integrityA, b: integrityB},
  validation,
  datasets,
  legacyAgreement,
  formalProtocolEligible: formalBlockers.length === 0,
  formalBlockers,
  limitations: [
    'Agreement does not prove that work was performed independently or by humans.',
    'Legacy agreement compares against existing scorer labels; it is not an independent truth estimate.',
    'A first-look or development dataset cannot regain unseen holdout status through post-hoc annotation.'
  ]
};

fs.mkdirSync(outputDir, {recursive: true});
const jsonOutput = path.join(outputDir, 'human-review-analysis.json');
const markdownOutput = path.join(outputDir, 'human-review-analysis.md');
writeJson(jsonOutput, report);
fs.writeFileSync(markdownOutput, markdown(report));
process.stdout.write(`${JSON.stringify({
  formalProtocolEligible: report.formalProtocolEligible,
  formalBlockers: report.formalBlockers,
  datasets: report.datasets.map(item => ({
    datasetId: item.datasetId,
    cases: item.cases,
    relationObservations: item.semanticRelationAgreement.observations,
    relationAgreement: item.semanticRelationAgreement.observedAgreement,
    relationKappa: item.semanticRelationAgreement.kappa,
    disagreements: item.disagreements.length,
    structuralErrors: item.structuralErrors.length
  })),
  outputs: {json: jsonOutput, markdown: markdownOutput}
}, null, 2)}\n`);
