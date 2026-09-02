#!/usr/bin/env node
'use strict';

/**
 * Builds coordinator and blind reviewer materials for independent human
 * annotation. Existing gold files are copied byte-for-byte only into the
 * coordinator area; reviewer worksheets omit every scorer label and slice
 * that directly reveals an answer.
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const defaultOutput = path.join(root, 'target', 'ai-news-human-review-package-20260827');
const output = process.argv[2] ? path.resolve(process.argv[2]) : defaultOutput;
const guide = path.join(root, 'docs/zh/evidence/ai-news-human-annotation-guide-20260827.md');
const sourceRegistry = path.join(root,
    'newsclaw-server/src/main/resources/skills/ai_news_radar/references/source_registry.yml');

const datasetSpecs = [
  {
    key: 'relations-v2',
    file: 'newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-relations-development-v2.json',
    classification: 'development-regression-not-holdout',
    annotationScope: ['semantic-relations', 'risk', 'source-tier', 'deterministic-policy'],
    policyProtocol: 'current-v10',
    autonomousToolLabels: false
  },
  {
    key: 'tool-autonomy-v2',
    file: 'newsclaw-server/src/test/resources/evals/ai-news/live-agent-tool-autonomy-development-v2.json',
    classification: 'development-auto-regression-not-holdout',
    annotationScope: ['semantic-relations', 'risk', 'source-tier', 'deterministic-policy',
      'autonomous-tool-intent'],
    policyProtocol: 'current-v10',
    autonomousToolLabels: true
  },
  {
    key: 'sealed-v2-regression',
    file: 'newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-sealed-holdout-v2.json',
    classification: 'retired-after-first-look-regression-never-unseen-again',
    annotationScope: ['semantic-relations', 'risk', 'source-tier', 'frozen-v8-policy-audit'],
    policyProtocol: 'frozen-v8',
    autonomousToolLabels: false
  }
];

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function copy(source, destination) {
  fs.mkdirSync(path.dirname(destination), {recursive: true});
  fs.copyFileSync(source, destination);
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function parsePrompt(prompt) {
  const lines = String(prompt || '').split(/\r?\n/);
  const claimLine = lines.find(line => /^(主声明|Primary claim)/.test(line));
  if (!claimLine) throw new Error(`cannot find primary claim in prompt: ${prompt}`);
  const primaryClaim = claimLine.replace(/^[^:：]+[:：]\s*/, '').trim();
  const operationalLine = lines.find(line => /^(运行请求|Operational request)/.test(line));
  const operationalRequest = operationalLine
    ? operationalLine.replace(/^[^:：]+[:：]\s*/, '').trim() : null;
  const evidence = lines.filter(line => /^- [^|]+ \| URL /.test(line)).map(line => {
    const match = line.match(/^-\s*([^|]+?)\s*\|\s*URL\s+([^|]+?)\s*\|\s*publisher\s+([^|]+?)\s*\|\s*quote:\s*"(.*)"\s*$/);
    if (!match) throw new Error(`cannot parse evidence line: ${line}`);
    return {
      id: match[1].trim(),
      sourceUrl: match[2].trim(),
      publisherDisplayName: match[3].trim(),
      quote: match[4]
    };
  });
  if (evidence.length === 0) throw new Error(`cannot find evidence in prompt: ${prompt}`);
  return {primaryClaim, operationalRequest, evidence};
}

function blankAnnotation(evidence, includeAutonomousToolLabels) {
  return {
    evidenceRelations: evidence.map(item => ({
      evidenceId: item.id,
      relation: null,
      confidence: null,
      rationale: ''
    })),
    riskLevel: null,
    policyDecision: {
      sourceTier: null,
      verificationEligible: null,
      citationAllowed: null,
      claimQuoteSupported: null,
      refusalRequired: null,
      unresolvedConflict: null,
      humanReviewRequired: null
    },
    autonomousToolExpectation: includeAutonomousToolLabels ? {
      mode: null,
      toolName: null,
      arguments: null,
      rationale: ''
    } : null,
    caseValid: null,
    issueCodes: [],
    notes: ''
  };
}

function blindCase(item, spec) {
  const parsed = parsePrompt(item.prompt);
  const packet = item.policyPacket || {};
  const packetEvidence = Array.isArray(packet.evidence) ? packet.evidence : [];
  const publisherById = new Map(parsed.evidence.map(evidence =>
    [String(evidence.id), evidence.publisherDisplayName]));
  const evidence = packetEvidence.length > 0 ? packetEvidence.map(evidence => ({
    id: String(evidence.id),
    sourceUrl: String(evidence.sourceUrl || ''),
    publisherDisplayName: publisherById.get(String(evidence.id)) || '',
    quote: String(evidence.quote || '')
  })) : parsed.evidence;
  const primaryClaim = packet.primaryClaim || parsed.primaryClaim;
  const operationalRequest = packet.operationalRequest || parsed.operationalRequest;
  return {
    caseId: item.id,
    language: item.slices && item.slices.language ? item.slices.language : null,
    taskInput: {
      operationalRequest: operationalRequest || null,
      primaryClaim,
      evidence,
      allowedCitationIds: Array.isArray(item.allowedCitationIds)
        ? [...item.allowedCitationIds] : evidence.map(value => value.id),
      requestedCitationId: item.requestedCitationId == null
        ? null : String(item.requestedCitationId),
      declaredConflict: packet.declaredConflict === true
    },
    annotation: blankAnnotation(evidence, spec.autonomousToolLabels)
  };
}

function worksheet(dataset, spec, sourceHash, reviewerSlot) {
  return {
    schemaVersion: '1.0',
    worksheetType: 'blind-independent-human-review',
    reviewer: {
      slot: reviewerSlot,
      reviewerId: '',
      organizationOrTeam: '',
      startedAt: '',
      completedAt: '',
      attestsNoGoldOrOtherReviewerExposure: false,
      attestsIndependentHumanWork: false
    },
    sourceDataset: {
      datasetId: dataset.datasetId,
      datasetVersion: dataset.datasetVersion,
      sha256: sourceHash,
      classification: spec.classification,
      policyProtocol: spec.policyProtocol,
      originalFilename: path.basename(spec.file)
    },
    annotationScope: spec.annotationScope,
    allowedValues: {
      semanticRelation: ['entails', 'contradicts', 'partial', 'hedged', 'unrelated'],
      riskLevel: ['normal', 'high'],
      sourceTier: ['official', 'media', 'community'],
      autonomousToolMode: spec.autonomousToolLabels ? ['required', 'forbidden'] : [],
      issueCodes: [
        'ambiguous-relation', 'ambiguous-risk', 'bad-translation-or-language',
        'duplicate-or-missing-evidence-id', 'source-registry-ambiguity',
        'citation-input-ambiguity', 'tool-intent-ambiguity', 'policy-rule-ambiguity',
        'protocol-disagreement', 'other'
      ]
    },
    cases: dataset.cases.map(item => blindCase(item, spec))
  };
}

if (fs.existsSync(output)) {
  throw new Error(`refusing to overwrite existing review package: ${output}`);
}
fs.mkdirSync(output, {recursive: true});

copy(guide, path.join(output, 'ANNOTATION-GUIDE.md'));
copy(sourceRegistry, path.join(output, 'reference', 'source_registry.yml'));
for (const slot of ['reviewer-a', 'reviewer-b']) {
  copy(guide, path.join(output, slot, 'ANNOTATION-GUIDE.md'));
  copy(sourceRegistry, path.join(output, slot, 'reference', 'source_registry.yml'));
}

const manifestDatasets = [];
const adjudicationDatasets = [];
for (const spec of datasetSpecs) {
  const source = path.join(root, spec.file);
  const dataset = JSON.parse(fs.readFileSync(source, 'utf8'));
  if (!Array.isArray(dataset.cases) || dataset.cases.length === 0) {
    throw new Error(`dataset has no cases: ${spec.file}`);
  }
  const sourceHash = sha256(source);
  const originalDestination = path.join(output, 'originals', path.basename(spec.file));
  copy(source, originalDestination);

  const worksheetPaths = {};
  for (const reviewerSlot of ['reviewer-a', 'reviewer-b']) {
    const worksheetFile = `${spec.key}.annotations.json`;
    const destination = path.join(output, reviewerSlot, worksheetFile);
    writeJson(destination, worksheet(dataset, spec, sourceHash, reviewerSlot));
    worksheetPaths[reviewerSlot] = {
      file: path.relative(output, destination),
      blankTemplateSha256: sha256(destination)
    };
  }
  manifestDatasets.push({
    key: spec.key,
    datasetId: dataset.datasetId,
    datasetVersion: dataset.datasetVersion,
    cases: dataset.cases.length,
    originalFile: path.relative(output, originalDestination),
    originalSha256: sourceHash,
    classification: spec.classification,
    policyProtocol: spec.policyProtocol,
    annotationScope: spec.annotationScope,
    worksheets: worksheetPaths
  });
  adjudicationDatasets.push({
    key: spec.key,
    sourceDatasetSha256: sourceHash,
    reviewerACompletedWorksheetSha256: '',
    reviewerBCompletedWorksheetSha256: '',
    cases: dataset.cases.map(item => ({
      caseId: item.id,
      disagreementFields: [],
      finalAnnotation: null,
      adjudicationRationale: '',
      status: 'pending'
    }))
  });
}

writeJson(path.join(output, 'coordinator', 'adjudication-template.json'), {
  schemaVersion: '1.0',
  adjudicator: {
    adjudicatorId: '',
    startedAt: '',
    completedAt: ''
  },
  requiredAgreementReport: {
    exactAgreementByField: null,
    semanticRelationCohensKappa: null,
    disagreements: null,
    adjudicated: null
  },
  datasets: adjudicationDatasets
});

writeJson(path.join(output, 'manifest.json'), {
  schemaVersion: '1.0',
  packageDate: '2026-08-27',
  purpose: 'coordinator originals plus gold-blind independent human annotation worksheets',
  distributionRule: 'Never send originals or the other reviewer directory to a reviewer.',
  datasets: manifestDatasets
});

process.stdout.write(`${JSON.stringify({
  output,
  datasets: manifestDatasets.map(item => ({
    key: item.key,
    cases: item.cases,
    sha256: item.originalSha256
  }))
}, null, 2)}\n`);
