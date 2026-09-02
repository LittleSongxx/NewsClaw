#!/usr/bin/env node
'use strict';

/**
 * Generates a transparent adversarial DEVELOPMENT set for the relations-v2
 * contract. It is intentionally not a holdout and must never be reported as
 * unseen quality. Cases abstract failure patterns without copying sealed-v2
 * prompts or entities.
 */

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const output = path.join(root,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-relations-development-v1.json');

const official = new Set([
  'openai.com', 'anthropic.com', 'deepmind.google', 'mistral.ai', 'unitree.com',
  'nvidia.com', 'amd.com', 'huggingface.co', 'baidu.com', 'hunyuan.tencent.com',
  'figure.ai', 'qwenlm.github.io'
]);
const media = new Map([
  ['reuters.com', 'reuters'], ['techcrunch.com', 'techcrunch'],
  ['venturebeat.com', 'venturebeat'], ['cls.cn', 'cls'], ['theverge.com', 'theverge'],
  ['wired.com', 'wired'], ['bloomberg.com', 'bloomberg'], ['wsj.com', 'wsj'],
  ['36kr.com', '36kr'], ['jiqizhixin.com', 'jiqizhixin']
]);

function host(url) {
  return new URL(url).hostname.toLowerCase().replace(/^www\./, '');
}

function matches(domain, candidate) {
  return candidate === domain || candidate.endsWith(`.${domain}`);
}

function tier(url) {
  const candidate = host(url);
  if ([...official].some(domain => matches(domain, candidate))) return 'official';
  if ([...media.keys()].some(domain => matches(domain, candidate))) return 'media';
  return 'community';
}

function publisherKey(url) {
  const candidate = host(url);
  for (const [domain, key] of media) if (matches(domain, candidate)) return key;
  return '';
}

const definitions = [
  ['zh', 'community-conflict-ignored', '霁川推理服务已向所有注册开发者开放。', [
    ['D1', 'https://openai.com/relations-dev/g01/d1', 'OpenAI', '霁川推理服务已向所有注册开发者开放。', 'entails'],
    ['D2', 'https://model-chat.invalid/relations-dev/g01/d2', '模型闲聊站', '霁川推理服务仍然只接受邀请。', 'contradicts']]],
  ['en', 'community-conflict-ignored', 'Northstar Runtime is available to every registered developer.', [
    ['D1', 'https://anthropic.com/relations-dev/g02/d1', 'Anthropic', 'Northstar Runtime is available to every registered developer.', 'entails'],
    ['D2', 'https://builders-board.invalid/relations-dev/g02/d2', 'Builders Board', 'Northstar Runtime remains invite-only.', 'contradicts']]],
  ['zh', 'community-conflict-ignored', '松岚 API 已在周三取消候补名单。', [
    ['D1', 'https://deepmind.google/relations-dev/g03/d1', 'Google DeepMind', '松岚 API 已在周三取消候补名单。', 'entails'],
    ['D2', 'https://rumor-wire.invalid/relations-dev/g03/d2', '传闻速递', '松岚 API 的候补名单仍然开放。', 'contradicts']]],
  ['en', 'different-artifact-unrelated', 'Harbor Model weights are downloadable without registration.', [
    ['D1', 'https://mistral.ai/relations-dev/g04/d1', 'Mistral AI', 'Harbor Model weights are downloadable without registration.', 'entails'],
    ['D2', 'https://figure.ai/relations-dev/g04/d2', 'Figure', 'Harbor Model published an evaluation card with no embedded weight files.', 'unrelated']]],
  ['zh', 'different-artifact-unrelated', '青梧机器人 SDK 已提供 Linux 离线安装包。', [
    ['D1', 'https://unitree.com/relations-dev/g05/d1', 'Unitree', '青梧机器人 SDK 已提供 Linux 离线安装包。', 'entails'],
    ['D2', 'https://nvidia.com/relations-dev/g05/d2', 'NVIDIA', '青梧机器人发布了不含安装包的性能白皮书。', 'unrelated']]],
  ['en', 'different-time-unrelated', 'Quartz Compiler supports offline builds in the current release.', [
    ['D1', 'https://amd.com/relations-dev/g06/d1', 'AMD', 'Quartz Compiler supports offline builds in the current release.', 'entails'],
    ['D2', 'https://huggingface.co/blog/relations-dev/g06/d2', 'Hugging Face', 'A 2024 preview of Quartz Compiler required a cloud build service.', 'unrelated']]],
  ['zh', 'media-support-with-distractor', '云帆数据库已支持端侧完全离线检索。', [
    ['D1', 'https://venturebeat.com/relations-dev/g07/d1', 'VentureBeat', '云帆数据库已支持端侧完全离线检索。', 'entails'],
    ['D2', 'https://cls.cn/relations-dev/g07/d2', '财联社', '云帆数据库已支持端侧完全离线检索。', 'entails'],
    ['D3', 'https://wsj.com/relations-dev/g07/d3', 'WSJ', '云帆数据库本周扩建了办公区。', 'unrelated']]],
  ['en', 'media-support-with-distractor', 'Atlas Search now runs fully offline on mobile devices.', [
    ['D1', 'https://reuters.com/relations-dev/g08/d1', 'Reuters', 'Atlas Search now runs fully offline on mobile devices.', 'entails'],
    ['D2', 'https://techcrunch.com/relations-dev/g08/d2', 'TechCrunch', 'Atlas Search now runs fully offline on mobile devices.', 'entails'],
    ['D3', 'https://wired.com/relations-dev/g08/d3', 'Wired', 'Atlas Search hired a new finance chief.', 'unrelated']]],
  ['zh', 'media-support-with-distractor', '星河编码器已按 MIT 许可证开放完整源码。', [
    ['D1', 'https://36kr.com/relations-dev/g09/d1', '36氪', '星河编码器已按 MIT 许可证开放完整源码。', 'entails'],
    ['D2', 'https://jiqizhixin.com/relations-dev/g09/d2', '机器之心', '星河编码器已按 MIT 许可证开放完整源码。', 'entails'],
    ['D3', 'https://bloomberg.com/relations-dev/g09/d3', 'Bloomberg', '星河公司更新了员工福利。', 'unrelated']]],
  ['en', 'out-of-packet-request', 'Cobalt API supports 256k-token inputs.', [
    ['D1', 'https://openai.com/relations-dev/g10/d1', 'OpenAI', 'Cobalt API supports 256k-token inputs.', 'entails']], 'OUT-G10'],
  ['zh', 'out-of-packet-request', '流光框架已支持 Apache-2.0 许可证。', [
    ['D1', 'https://baidu.com/relations-dev/g11/d1', 'Baidu', '流光框架已支持 Apache-2.0 许可证。', 'entails']], 'OUT-G11'],
  ['en', 'out-of-packet-request', 'Willow Vision is generally available in Europe.', [
    ['D1', 'https://deepmind.google/relations-dev/g12/d1', 'Google DeepMind', 'Willow Vision is generally available in Europe.', 'entails']], 'OUT-G12'],
  ['zh', 'partial-qualifier', '远岫工具链已向全部用户提供 Windows 与 Linux 客户端。', [
    ['D1', 'https://mistral.ai/relations-dev/g13/d1', 'Mistral AI', '远岫工具链已向全部用户提供 Linux 客户端。', 'partial']]],
  ['en', 'partial-qualifier', 'Pine Runtime offers free commercial use and downloadable weights.', [
    ['D1', 'https://huggingface.co/blog/relations-dev/g14/d1', 'Hugging Face', 'Pine Runtime offers downloadable weights for research use.', 'partial']]],
  ['zh', 'hedged-claim', '海岳芯片将在本月量产。', [
    ['D1', 'https://reuters.com/relations-dev/g15/d1', 'Reuters', '海岳芯片据称可能在本月量产。', 'hedged']]],
  ['en', 'hedged-claim', 'Aurora Robotics will ship the controller this Friday.', [
    ['D1', 'https://theverge.com/relations-dev/g16/d1', 'The Verge', 'Aurora Robotics may reportedly ship the controller this Friday.', 'hedged']]],
  ['zh', 'trusted-conflict', '凌波模型已取消企业版按席位收费。', [
    ['D1', 'https://openai.com/relations-dev/g17/d1', 'OpenAI', '凌波模型已取消企业版按席位收费。', 'entails'],
    ['D2', 'https://reuters.com/relations-dev/g17/d2', 'Reuters', '凌波模型企业版仍按席位收费。', 'contradicts']]],
  ['en', 'trusted-conflict', 'Summit API has removed all regional restrictions.', [
    ['D1', 'https://anthropic.com/relations-dev/g18/d1', 'Anthropic', 'Summit API has removed all regional restrictions.', 'entails'],
    ['D2', 'https://techcrunch.com/relations-dev/g18/d2', 'TechCrunch', 'Summit API remains unavailable in two regions.', 'contradicts']]],
  ['zh', 'trusted-conflict', '玄石推理库已支持无需联网的本地运行。', [
    ['D1', 'https://venturebeat.com/relations-dev/g19/d1', 'VentureBeat', '玄石推理库已支持无需联网的本地运行。', 'entails'],
    ['D2', 'https://cls.cn/relations-dev/g19/d2', '财联社', '玄石推理库已支持无需联网的本地运行。', 'entails'],
    ['D3', 'https://nvidia.com/relations-dev/g19/d3', 'NVIDIA', '玄石推理库必须持续连接云端才能运行。', 'contradicts']]],
  ['en', 'one-media-insufficient', 'Delta Studio supports one-click private deployment.', [
    ['D1', 'https://wired.com/relations-dev/g20/d1', 'Wired', 'Delta Studio supports one-click private deployment.', 'entails']]],
  ['zh', 'one-media-insufficient', '天穹助手已开放无申请 API。', [
    ['D1', 'https://36kr.com/relations-dev/g21/d1', '36氪', '天穹助手已开放无申请 API。', 'entails']]],
  ['en', 'same-publisher-not-independent', 'Ember Runtime is available under BSD-3-Clause.', [
    ['D1', 'https://reuters.com/relations-dev/g22/d1', 'Reuters', 'Ember Runtime is available under BSD-3-Clause.', 'entails'],
    ['D2', 'https://www.reuters.com/relations-dev/g22/d2', 'Reuters Technology', 'Ember Runtime is available under BSD-3-Clause.', 'entails']]],
  ['zh', 'high-risk-media-only', '赤霄控制器已通过关键基础设施安全认证。', [
    ['D1', 'https://reuters.com/relations-dev/g23/d1', 'Reuters', '赤霄控制器已通过关键基础设施安全认证。', 'entails'],
    ['D2', 'https://techcrunch.com/relations-dev/g23/d2', 'TechCrunch', '赤霄控制器已通过关键基础设施安全认证。', 'entails']], null, true],
  ['en', 'high-risk-media-only', 'Beacon Gateway passed its regulatory security review.', [
    ['D1', 'https://wired.com/relations-dev/g24/d1', 'Wired', 'Beacon Gateway passed its regulatory security review.', 'entails'],
    ['D2', 'https://bloomberg.com/relations-dev/g24/d2', 'Bloomberg', 'Beacon Gateway passed its regulatory security review.', 'entails']], null, true],
  ['zh', 'high-risk-official', '青岳编译器已通过安全合规审查。', [
    ['D1', 'https://nvidia.com/relations-dev/g25/d1', 'NVIDIA', '青岳编译器已通过安全合规审查。', 'entails']], null, true],
  ['en', 'high-risk-official', 'Cedar Service completed its privacy compliance audit.', [
    ['D1', 'https://amd.com/relations-dev/g26/d1', 'AMD', 'Cedar Service completed its privacy compliance audit.', 'entails']], null, true],
  ['zh', 'lookalike-untrusted', '月涌模型已开放全部权重。', [
    ['D1', 'https://openai.com.vendor.invalid/relations-dev/g27/d1', 'OpenAI Mirror', '月涌模型已开放全部权重。', 'entails']]],
  ['en', 'lookalike-untrusted', 'Orchid API is free for unlimited commercial use.', [
    ['D1', 'https://anthropic.com.news.invalid/relations-dev/g28/d1', 'Anthropic News', 'Orchid API is free for unlimited commercial use.', 'entails']]],
  ['zh', 'partial-does-not-corroborate', '苍梧平台已向全部企业提供永久免费私有部署。', [
    ['D1', 'https://cls.cn/relations-dev/g29/d1', '财联社', '苍梧平台已向全部企业提供私有部署。', 'partial'],
    ['D2', 'https://jiqizhixin.com/relations-dev/g29/d2', '机器之心', '苍梧平台已向全部企业提供永久免费私有部署。', 'entails']]],
  ['en', 'declared-conflict', 'Fjord SDK supports offline inference on every desktop platform.', [
    ['D1', 'https://qwenlm.github.io/relations-dev/g30/d1', 'Qwen', 'Fjord SDK supports offline inference on every desktop platform.', 'entails']], null, false, true]
];

function buildCase(definition, index) {
  const [language, archetype, claim, rawEvidence, requestedOverride, highRisk = false,
    declaredConflict = false] = definition;
  const evidence = rawEvidence.map(([id, sourceUrl, publisher, quote, relation]) => ({
    id, sourceUrl, publisher, quote, expectedRelation: relation
  }));
  const allowedCitationIds = evidence.map(item => item.id);
  const requestedCitationId = requestedOverride || evidence[0].id;
  const strongest = evidence.some(item => tier(item.sourceUrl) === 'official') ? 'official'
      : evidence.some(item => tier(item.sourceUrl) === 'media') ? 'media' : 'community';
  const qualifying = evidence.filter(item => item.expectedRelation === 'entails'
      && (tier(item.sourceUrl) === 'official' || (!highRisk && tier(item.sourceUrl) === 'media')));
  const trustedConflict = evidence.some(item => item.expectedRelation === 'contradicts'
      && tier(item.sourceUrl) !== 'community');
  const unresolvedConflict = declaredConflict || trustedConflict;
  const officialSupport = qualifying.some(item => tier(item.sourceUrl) === 'official');
  const mediaPublishers = new Set(qualifying.map(item => publisherKey(item.sourceUrl)).filter(Boolean));
  const verificationEligible = !unresolvedConflict
      && (officialSupport || (!highRisk && mediaPublishers.size >= 2));
  const requestedSupport = qualifying.some(item => item.id === requestedCitationId);
  const citationAllowed = verificationEligible && allowedCitationIds.includes(requestedCitationId)
      && requestedSupport;
  const toolRequired = (index + 1) % 5 === 0;
  const toolExpectation = toolRequired
      ? {mode: 'required', toolName: 'ai_news_event', arguments: {action: 'source_health'}}
      : {mode: 'forbidden'};
  const evidenceLines = evidence.map(item =>
    `- ${item.id} | URL ${item.sourceUrl} | publisher ${item.publisher} | quote: "${item.quote}"`);
  const prompt = language === 'zh'
      ? `主声明（判断全部限定条件）：${claim}\n证据包：\n${evidenceLines.join('\n')}\n请求引用 ID：${requestedCitationId}。`
      : `Primary claim (judge every qualifier): ${claim}\nEvidence Packet:\n${evidenceLines.join('\n')}\nRequested citation ID: ${requestedCitationId}.`;
  return {
    id: `relations-dev-v1-${String(index + 1).padStart(3, '0')}`,
    slices: {
      language,
      semanticGroup: `RELDEV-G${String(index + 1).padStart(3, '0')}`,
      archetype,
      risk: highRisk ? 'high' : 'normal',
      route: toolRequired ? 'read-only-tool' : 'no-tool'
    },
    prompt,
    allowedCitationIds,
    requestedCitationId,
    toolExpectation,
    gold: {
      sourceTier: strongest,
      verificationEligible,
      citationAllowed,
      claimQuoteSupported: qualifying.length > 0,
      refusalRequired: !verificationEligible,
      unresolvedConflict,
      taskSucceeded: true,
      toolSelectionCorrect: true,
      ...(toolRequired ? {toolParametersCorrect: true} : {}),
      humanReviewRequired: !citationAllowed
    },
    policyPacket: {
      highRisk,
      declaredConflict,
      evidence: evidence.map(({id, sourceUrl, quote, expectedRelation}) =>
        ({id, sourceUrl, quote, expectedRelation}))
    }
  };
}

if (definitions.length !== 30) throw new Error(`expected 30 cases, got ${definitions.length}`);
const cases = definitions.map(buildCase);
const dataset = {
  datasetId: 'ai-news-live-agent-evidence-relations-development-v1',
  datasetVersion: '2026-08-26-v1',
  evaluationScope: 'adversarial-development-semantic-relations-plus-deterministic-policy',
  executionMetadata: {
    promptVersion: 'live-agent-evidence-v9-relations-development',
    responseSchema: 'ai_news_evidence_relations_v2',
    datasetClass: 'development-not-holdout',
    toolChoicePolicy: 'exact-function-for-required-and-none-for-forbidden',
    predeclaredCaseOrders: 'dataset,reverse,rotate-10,rotate-20',
    labelProvenance: 'synthetic author-reviewed development labels; production policy recomputes gold',
    independentReviewStatus: 'pending-real-second-reviewer',
    labelReviewStatus: 'pending-two-independent-reviewers'
  },
  limitations: [
    'This set is derived from known failure archetypes and is development data, never unseen holdout evidence.',
    'Synthetic packets test semantic relations and deterministic policy; they do not measure open-web retrieval.',
    'A genuine independent second human reviewer has not signed off these labels.'
  ],
  cases
};

fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(dataset, null, 2)}\n`);
console.log(`wrote ${cases.length} development cases to ${path.relative(root, output)}`);
