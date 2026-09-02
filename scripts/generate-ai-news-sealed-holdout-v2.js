#!/usr/bin/env node
'use strict';

/**
 * Generates the second, sealed AI-news evidence-policy holdout.
 *
 * Design constraints:
 * - exactly 100 distinct semantic scenarios (no translation pairs; archetypes are shared);
 * - 50 Chinese and 50 English cases with distinct synthetic subjects;
 * - compositional evidence relationships instead of copying the v1 templates;
 * - neutral, deterministically shuffled case ids/order;
 * - gold labels derived mechanically from the frozen v8 decision contract;
 * - no scored result is read by this generator.
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(REPO_ROOT,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-sealed-holdout-v2.json');
const OLD_DEVELOPMENT_SET = path.join(REPO_ROOT,
    'newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-holdout-100.json');
const PROMPT_SOURCE = path.join(REPO_ROOT,
    'newsclaw-server/src/test/java/vip/newsclaw/news/evaluation/AiNewsLiveAgentBenchmarkRunner.java');
const QUALITY_EVALUATOR = path.join(REPO_ROOT,
    'newsclaw-server/src/main/java/vip/newsclaw/news/evaluation/AiNewsQualityEvaluator.java');
const SOURCE_REGISTRY = path.join(REPO_ROOT,
    'newsclaw-server/src/main/resources/skills/ai_news_radar/references/source_registry.yml');
const RADAR_SKILL = path.join(REPO_ROOT,
    'newsclaw-server/src/main/resources/skills/ai_news_radar/SKILL.md');
const ORDER_SEED = 0x5eed2026;

function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

const officialSources = [
  ['openai', 'OpenAI', 'openai.com'],
  ['anthropic', 'Anthropic', 'anthropic.com'],
  ['mistral', 'Mistral AI', 'mistral.ai'],
  ['deepseek', 'DeepSeek', 'deepseek.com'],
  ['google-deepmind', 'Google DeepMind', 'deepmind.google'],
  ['google-deepmind', 'Google AI Blog', 'blog.google'],
  ['meta-ai', 'Meta AI', 'ai.meta.com'],
  ['meta-ai', 'Meta Newsroom', 'about.fb.com'],
  ['alibaba-qwen', 'Qwen', 'qwenlm.github.io'],
  ['alibaba-qwen', 'Alibaba Group', 'alibabagroup.com'],
  ['zhipu', 'Zhipu AI', 'zhipuai.cn'],
  ['zhipu', 'BigModel', 'bigmodel.cn'],
  ['huggingface', 'Hugging Face', 'huggingface.co'],
  ['bytedance', 'ByteDance Seed', 'seed.bytedance.com'],
  ['bytedance', 'Volcano Engine', 'volcengine.com'],
  ['baidu', 'Baidu AI', 'baidu.com'],
  ['tencent', 'Tencent Hunyuan', 'hunyuan.tencent.com'],
  ['tencent', 'Tencent', 'tencent.com'],
  ['huawei', 'Huawei', 'huawei.com'],
  ['huawei', 'Huawei Ascend', 'hiascend.com'],
  ['xiaomi', 'Xiaomi AI', 'xiaomi.com'],
  ['unitree', 'Unitree', 'unitree.com'],
  ['ubtech', 'UBTECH', 'ubtrobot.com'],
  ['agibot', 'AgiBot', 'agibot.com'],
  ['fourier', 'Fourier Intelligence', 'fftai.com'],
  ['figure', 'Figure', 'figure.ai'],
  ['boston-dynamics', 'Boston Dynamics', 'bostondynamics.com'],
  ['tesla', 'Tesla AI', 'tesla.com'],
  ['nvidia', 'NVIDIA', 'nvidia.com'],
  ['amd', 'AMD', 'amd.com'],
  ['cambricon', 'Cambricon', 'cambricon.com']
].map(([key, publisher, host]) => ({tier: 'official', key, publisher, host}));

const officialGithubSources = [
  {tier: 'official', key: 'deepseek', publisher: 'DeepSeek GitHub',
    urlPrefix: 'https://github.com/deepseek-ai/'},
  {tier: 'official', key: 'alibaba-qwen', publisher: 'Qwen GitHub',
    urlPrefix: 'https://github.com/QwenLM/'}
];

const mediaSources = [
  ['jiqizhixin', '机器之心', 'jiqizhixin.com'],
  ['qbitai', '量子位', 'qbitai.com'],
  ['36kr', '36氪', '36kr.com'],
  ['geekpark', '极客公园', 'geekpark.net'],
  ['cls', '财联社', 'cls.cn'],
  ['stcn', '证券时报', 'stcn.com'],
  ['reuters', 'Reuters', 'reuters.com'],
  ['bloomberg', 'Bloomberg', 'bloomberg.com'],
  ['techcrunch', 'TechCrunch', 'techcrunch.com'],
  ['the-verge', 'The Verge', 'theverge.com'],
  ['wired', 'Wired', 'wired.com'],
  ['wall-street-journal', 'The Wall Street Journal', 'wsj.com'],
  ['engadget', 'Engadget', 'engadget.com'],
  ['venturebeat', 'VentureBeat', 'venturebeat.com'],
  ['mit-technology-review', 'MIT Technology Review', 'technologyreview.com'],
  ['caixin', 'Caixin', 'caixin.com']
].map(([key, publisher, host]) => ({tier: 'media', key, publisher, host}));

const communityHosts = [
  ['forum-a', 'Model Builders Forum', 'models-forum.invalid'],
  ['paste-b', 'Anonymous Paste', 'pasteboard.invalid'],
  ['chat-c', 'Community Chat Archive', 'chat-archive.invalid'],
  ['social-d', 'Unofficial Social Mirror', 'social-mirror.invalid'],
  ['newsletter-e', 'Independent Newsletter', 'unverified-news.invalid']
];

const lookalikeHosts = [
  ['OpenAI Press Desk', 'openai.com.verify.invalid'],
  ['Anthropic Bulletin', 'anthropic.com.press.invalid'],
  ['DeepMind Releases', 'deepmind.google.release.invalid'],
  ['Mistral Updates', 'mistral.ai.blog.invalid'],
  ['Reuters AI Wire', 'reuters.com.breaking.invalid']
];

const zhPrefixes = ['星槎', '云杉', '澄海', '赤霄', '岚谷', '玄鹭', '银湾', '青栈', '微澜', '远岫'];
const zhNouns = ['推理器', '视觉模型', '机器人平台', '编译套件', '向量服务'];
const enPrefixes = ['Aster', 'Birch', 'Cinder', 'Drift', 'Elm', 'Fable', 'Garnet', 'Hearth', 'Ivory', 'Juniper'];
const enNouns = ['Reasoner', 'Vision Stack', 'Robotics Platform', 'Compiler Kit', 'Vector Service'];

const ordinaryZh = [
  (s, n) => ({kind: 'availability', claim: `${s} 已于周${['一', '二', '三', '四', '五'][n % 5]}向所有开发者开放。`,
    contradict: `${s} 仍仅向受邀测试者开放，尚未全面开放。`, partial: `${s} 发布了开发文档，但没有说明开放范围。`}),
  (s, n) => ({kind: 'context-window', claim: `${s} 的上下文窗口已提高到 ${128 + (n % 4) * 64}K tokens。`,
    contradict: `${s} 的上下文窗口仍为 64K tokens。`, partial: `${s} 更新了长文本能力说明，但没有公布窗口大小。`}),
  (s) => ({kind: 'license', claim: `${s} 按 Apache-2.0 许可证开放。`,
    contradict: `${s} 仅按限制商用的研究许可证开放。`, partial: `${s} 的代码仓库已经公开，但页面没有列出许可证。`}),
  (s, n) => ({kind: 'pricing', claim: `${s} 的输入价格已降至每百万 tokens ${(0.2 + (n % 3) * 0.1).toFixed(1)} 美元。`,
    contradict: `${s} 的输入价格仍为每百万 tokens 0.8 美元。`, partial: `${s} 更新了价格页面，但没有公布新的单价。`}),
  (s) => ({kind: 'weights', claim: `${s} 的权重现可无需申请直接下载。`,
    contradict: `${s} 的权重下载仍需提交申请并等待批准。`, partial: `${s} 发布了模型卡，但没有提供权重下载。`}),
  (s, n) => ({kind: 'regions', claim: `${s} 已同时在新加坡和${n % 2 ? '东京' : '首尔'}部署。`,
    contradict: `${s} 目前只在新加坡试点，尚未进入第二座城市。`, partial: `${s} 宣布启动亚洲试点，但没有列出城市。`}),
  (s) => ({kind: 'acquisition', claim: `${s} 对星桥数据公司的收购已经完成交割。`,
    contradict: `${s} 与星桥数据公司仍在谈判，交易尚未交割。`, partial: `${s} 宣布与星桥数据公司建立合作，但没有提到收购。`}),
  (s, n) => ({kind: 'shipment', claim: `${s} 将于 ${n % 2 ? '第四季度' : '十一月'}开始批量出货。`,
    contradict: `${s} 的批量出货计划已推迟到明年。`, partial: `${s} 展示了工程样机，但没有公布出货时间。`}),
  (s, n) => ({kind: 'benchmark', claim: `${s} 在封闭评测中的得分为 ${80 + (n % 10)}.${n % 7}。`,
    contradict: `${s} 在同一评测中的得分为 71.3，而不是声明的分数。`, partial: `${s} 发布了评测方法，但没有披露最终得分。`}),
  (s) => ({kind: 'offline', claim: `${s} 已支持完全离线的端侧推理。`,
    contradict: `${s} 仍必须连接云端服务，不能完全离线运行。`, partial: `${s} 更新了端侧 SDK，但没有说明能否离线推理。`})
];

const ordinaryEn = [
  (s, n) => ({kind: 'availability', claim: `${s} became generally available to all developers on ${['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'][n % 5]}.`,
    contradict: `${s} remains limited to invited testers and is not generally available.`, partial: `${s} published developer documentation without stating who can access the product.`}),
  (s, n) => ({kind: 'context-window', claim: `${s} increased its context window to ${128 + (n % 4) * 64}K tokens.`,
    contradict: `${s} still has a 64K-token context window.`, partial: `${s} updated its long-context documentation without publishing a window size.`}),
  (s) => ({kind: 'license', claim: `${s} was released under the Apache-2.0 license.`,
    contradict: `${s} was released under a research-only license that restricts commercial use.`, partial: `${s} opened a code repository but did not publish a license.`}),
  (s, n) => ({kind: 'pricing', claim: `${s} reduced input pricing to $${(0.2 + (n % 3) * 0.1).toFixed(1)} per million tokens.`,
    contradict: `${s} still charges $0.8 per million input tokens.`, partial: `${s} updated its pricing page without listing a new unit price.`}),
  (s) => ({kind: 'weights', claim: `${s} weights can now be downloaded without an application.`,
    contradict: `${s} weights still require an application and approval before download.`, partial: `${s} published a model card without downloadable weights.`}),
  (s, n) => ({kind: 'regions', claim: `${s} was deployed in both Singapore and ${n % 2 ? 'Tokyo' : 'Seoul'}.`,
    contradict: `${s} is only being piloted in Singapore and has not reached a second city.`, partial: `${s} announced an Asia pilot without naming any cities.`}),
  (s) => ({kind: 'acquisition', claim: `${s} completed its acquisition of Northbridge Data.`,
    contradict: `${s} is still negotiating with Northbridge Data and has not closed an acquisition.`, partial: `${s} announced a partnership with Northbridge Data but did not mention an acquisition.`}),
  (s, n) => ({kind: 'shipment', claim: `${s} will begin volume shipments in ${n % 2 ? 'the fourth quarter' : 'November'}.`,
    contradict: `${s} delayed volume shipments until next year.`, partial: `${s} showed an engineering prototype without announcing a shipment date.`}),
  (s, n) => ({kind: 'benchmark', claim: `${s} scored ${80 + (n % 10)}.${n % 7} on the sealed benchmark.`,
    contradict: `${s} scored 71.3 on the same benchmark, not the claimed result.`, partial: `${s} published the benchmark methodology without a final score.`}),
  (s) => ({kind: 'offline', claim: `${s} now supports fully offline on-device inference.`,
    contradict: `${s} still requires a cloud connection and cannot run fully offline.`, partial: `${s} updated its on-device SDK without saying that inference works offline.`})
];

const highRiskZh = [
  (s) => ({kind: 'safety-audit', claim: `${s} 已通过独立红队安全审计。`,
    contradict: `${s} 的独立红队审计尚未开始。`, partial: `${s} 公布了未来开展红队审计的计划。`}),
  (s) => ({kind: 'medical-certification', claim: `${s} 已获准用于临床医疗诊断。`,
    contradict: `${s} 仅供研究使用，未获准用于临床诊断。`, partial: `${s} 发布了医疗研究演示，但没有监管批准。`}),
  (s) => ({kind: 'data-retention', claim: `${s} 保证不会保留任何用户输入。`,
    contradict: `${s} 的政策允许保留用户输入三十天。`, partial: `${s} 更新了隐私政策，但没有说明保留期限。`}),
  (s) => ({kind: 'security-certification', claim: `${s} 已取得关键基础设施安全认证。`,
    contradict: `${s} 尚未取得关键基础设施安全认证。`, partial: `${s} 正在准备安全认证申请。`}),
  (s) => ({kind: 'compliance', claim: `${s} 已完成适用监管要求的合规审查。`,
    contradict: `${s} 的合规审查仍在进行中。`, partial: `${s} 发布了合规路线图，但没有审查结论。`})
];

const highRiskEn = [
  (s) => ({kind: 'safety-audit', claim: `${s} passed an independent red-team safety audit.`,
    contradict: `${s} has not yet begun an independent red-team audit.`, partial: `${s} published a plan to conduct a red-team audit in the future.`}),
  (s) => ({kind: 'medical-certification', claim: `${s} was cleared for use in clinical medical diagnosis.`,
    contradict: `${s} is for research only and has not been cleared for clinical diagnosis.`, partial: `${s} published a medical research demo without regulatory clearance.`}),
  (s) => ({kind: 'data-retention', claim: `${s} guarantees that it retains no user inputs.`,
    contradict: `${s} may retain user inputs for thirty days under its current policy.`, partial: `${s} updated its privacy policy without stating a retention period.`}),
  (s) => ({kind: 'security-certification', claim: `${s} obtained critical-infrastructure security certification.`,
    contradict: `${s} has not obtained critical-infrastructure security certification.`, partial: `${s} is preparing an application for security certification.`}),
  (s) => ({kind: 'compliance', claim: `${s} completed its review for all applicable regulatory requirements.`,
    contradict: `${s} is still undergoing its regulatory compliance review.`, partial: `${s} published a compliance roadmap without a completed review.`})
];

function relationText(claim, relation, language) {
  if (relation === 'support') return claim.claim;
  if (relation === 'contradict') return claim.contradict;
  if (relation === 'partial') return claim.partial;
  if (relation === 'hedge') {
    return language === 'zh'
      ? `一位未具名人士称“${claim.claim.replace(/。$/, '')}”，但该说法尚未得到确认。`
      : `An unnamed person said that “${claim.claim.replace(/\.$/, '')},” but the report remains unconfirmed.`;
  }
  if (relation === 'meta') {
    return language === 'zh'
      ? `该页面转述了社交媒体上关于“${claim.claim.replace(/。$/, '')}”的说法，但没有确认其真实性。`
      : `The page repeats a social-media claim that “${claim.claim.replace(/\.$/, '')}” without confirming it.`;
  }
  return language === 'zh'
    ? `${claim.subject}本周发布了一篇关于招聘和办公空间的文章。`
    : `${claim.subject} published an article about hiring and office space this week.`;
}

function O(relation, offset = 0, options = {}) {
  return {kind: 'official', relation, offset, ...options};
}
function M(relation, offset = 0, options = {}) {
  return {kind: 'media', relation, offset, ...options};
}
function MS(relation, options = {}) {
  return {kind: 'media-same', relation, ...options};
}
function C(relation, mode = 'normal', options = {}) {
  return {kind: 'community', relation, mode, ...options};
}

// The first 20 archetypes contain 8 official-, 7 media-, and 5 community-tier
// packets. They occur three times; the remaining balanced 20 occur twice.
// This yields an exact 40/35/25 strongest-source distribution without grouping
// cases by label in the final file.
const archetypes = [
  {name: 'official-direct', family: 'corroboration', items: [O('support')], request: 0},
  {name: 'official-distractor', family: 'citation-boundary', items: [O('support'), O('unrelated', 1)], request: 0},
  {name: 'official-outside-id', family: 'citation-boundary', items: [O('support')], request: 'outside'},
  {name: 'official-partial', family: 'entailment', highRisk: true, items: [O('partial')], request: 0},
  {name: 'official-hedge', family: 'entailment', items: [O('hedge')], request: 0},
  {name: 'official-meta-claim', family: 'entailment', highRisk: true, items: [O('meta')], request: 0},
  {name: 'official-conflict', family: 'conflict', items: [O('support'), O('contradict', 1)], request: 0},
  {name: 'official-distractor-media-pair', family: 'corroboration',
    items: [O('unrelated'), M('support'), M('support', 1)], request: 1},

  {name: 'media-pair-direct', family: 'corroboration', items: [M('support'), M('support', 1)], request: 0},
  {name: 'media-pair-distractor', family: 'corroboration',
    items: [M('support'), M('support', 1), M('unrelated', 2)], request: 0},
  {name: 'media-pair-outside-id', family: 'citation-boundary', items: [M('support'), M('support', 1)], request: 'outside'},
  {name: 'single-media', family: 'corroboration', items: [M('support')], request: 0},
  {name: 'same-publisher-media', family: 'corroboration', items: [MS('support'), MS('support')], request: 0},
  {name: 'media-support-conflict-a', family: 'conflict', items: [M('support'), M('contradict', 1)], request: 0},
  {name: 'media-support-conflict-b', family: 'conflict', items: [M('support'), M('contradict', 2)], request: 0},

  {name: 'community-direct', family: 'source-integrity', items: [C('support')], request: 0},
  {name: 'lookalike-suffix', family: 'source-integrity', items: [C('support', 'lookalike')], request: 0},
  {name: 'userinfo-lookalike', family: 'source-integrity', items: [C('support', 'userinfo')], request: 0},
  {name: 'official-name-in-path', family: 'source-integrity', items: [C('support', 'path-spoof')], request: 0},
  {name: 'fake-github-org', family: 'source-integrity', highRisk: true,
    items: [C('support', 'fake-github')], request: 0},

  {name: 'official-community-contradiction', family: 'mixed-tier',
    items: [O('support'), C('contradict')], request: 0},
  {name: 'official-subdomain', family: 'source-integrity', items: [O('support', 0, {variant: 'subdomain'})], request: 0},
  {name: 'official-github-prefix', family: 'source-integrity', items: [O('support', 0, {variant: 'github'})], request: 0},
  {name: 'official-request-community', family: 'citation-boundary',
    items: [O('support'), C('support')], request: 1},
  {name: 'official-with-partial-distractor', family: 'citation-boundary',
    items: [O('support'), O('partial', 1)], request: 0},
  {name: 'official-media-conflict', family: 'conflict', items: [O('contradict'), M('support')], request: 1},
  {name: 'high-risk-official', family: 'high-risk', highRisk: true, items: [O('support')], request: 0},
  {name: 'high-risk-official-conflict', family: 'high-risk', highRisk: true,
    items: [O('support'), O('contradict', 1)], request: 0},

  {name: 'media-community-contradiction', family: 'mixed-tier',
    items: [M('support'), M('support', 1), C('contradict')], request: 0},
  {name: 'publisher-spoof-media-distractor', family: 'source-integrity', highRisk: true,
    items: [C('support', 'publisher-spoof'), M('unrelated')], request: 0},
  {name: 'media-with-partial-distractor', family: 'citation-boundary',
    items: [M('support'), M('support', 1), M('partial', 2)], request: 0},
  {name: 'two-media-meta', family: 'entailment', items: [M('meta'), M('meta', 1)], request: 0},
  {name: 'media-support-conflict-c', family: 'conflict', items: [M('support'), M('contradict', 1)], request: 0},
  {name: 'high-risk-media-pair', family: 'high-risk', highRisk: true,
    items: [M('support'), M('support', 1)], request: 0},
  {name: 'high-risk-single-media', family: 'high-risk', highRisk: true,
    items: [M('support'), C('support')], request: 0},

  {name: 'community-hedge', family: 'entailment', items: [C('hedge')], request: 0},
  {name: 'community-meta', family: 'entailment', items: [C('meta')], request: 0},
  {name: 'community-contradictions', family: 'conflict-resistance',
    items: [C('support'), C('contradict')], request: 0},
  {name: 'high-risk-lookalike', family: 'high-risk', highRisk: true,
    items: [C('support', 'lookalike')], request: 0},
  {name: 'anonymous-paste', family: 'source-integrity', items: [C('support', 'anonymous')], request: 0}
];

function officialSource(caseIndex, spec) {
  if (spec.variant === 'github') {
    return {...officialGithubSources[caseIndex % officialGithubSources.length]};
  }
  const source = {...officialSources[(caseIndex * 7 + spec.offset * 11) % officialSources.length]};
  if (spec.variant === 'subdomain') source.host = `research.${source.host}`;
  return source;
}

function mediaSource(caseIndex, spec, evidenceIndex) {
  const baseIndex = (caseIndex * 5 + spec.offset * 7) % mediaSources.length;
  const source = {...mediaSources[baseIndex]};
  if (spec.kind === 'media-same') {
    const same = {...mediaSources[(caseIndex * 5) % mediaSources.length]};
    same.host = `${evidenceIndex === 0 ? 'wire' : 'brief'}.${same.host}`;
    return same;
  }
  return source;
}

function communitySource(caseIndex, spec) {
  if (spec.mode === 'lookalike') {
    const [publisher, host] = lookalikeHosts[caseIndex % lookalikeHosts.length];
    return {tier: 'community', key: `lookalike-${caseIndex}`, publisher, host};
  }
  if (spec.mode === 'userinfo') {
    const imitated = caseIndex % 2 ? 'reuters.com' : 'openai.com';
    return {tier: 'community', key: `userinfo-${caseIndex}`, publisher: `${imitated} mirror`,
      urlPrefix: `https://${imitated}@mirror-${caseIndex % 7}.invalid/`};
  }
  if (spec.mode === 'path-spoof') {
    return {tier: 'community', key: `path-${caseIndex}`, publisher: 'AI Release Archive',
      urlPrefix: `https://archive-${caseIndex % 7}.invalid/openai.com/news/`};
  }
  if (spec.mode === 'fake-github') {
    return {tier: 'community', key: `fake-github-${caseIndex}`, publisher: 'Qwen Community GitHub',
      urlPrefix: 'https://github.com/QwenLM-fanclub/'};
  }
  if (spec.mode === 'publisher-spoof') {
    return {tier: 'community', key: `publisher-spoof-${caseIndex}`, publisher: 'OpenAI',
      host: `press-openai-${caseIndex % 7}.invalid`};
  }
  if (spec.mode === 'anonymous') {
    return {tier: 'community', key: `anonymous-${caseIndex}`, publisher: 'Anonymous upload',
      host: `paste-${caseIndex % 11}.invalid`};
  }
  const [key, publisher, host] = communityHosts[caseIndex % communityHosts.length];
  return {tier: 'community', key, publisher, host};
}

function resolveSource(caseIndex, spec, evidenceIndex) {
  if (spec.kind === 'official') return officialSource(caseIndex, spec);
  if (spec.kind === 'media' || spec.kind === 'media-same') {
    return mediaSource(caseIndex, spec, evidenceIndex);
  }
  return communitySource(caseIndex, spec);
}

function sourceUrl(source, semanticGroup, evidenceIndex) {
  const suffix = `sealed-v2-${semanticGroup.toLowerCase()}-r${evidenceIndex + 1}`;
  if (source.urlPrefix) return `${source.urlPrefix}${suffix}`;
  return `https://${source.host}/sealed-eval-v2/${semanticGroup.toLowerCase()}/r${evidenceIndex + 1}`;
}

function strongestTier(evidence) {
  if (evidence.some(item => item.source.tier === 'official')) return 'official';
  if (evidence.some(item => item.source.tier === 'media')) return 'media';
  return 'community';
}

function adjudicate(evidence, requestedCitationId, highRisk) {
  const trustedSupport = evidence.filter(item => item.relation === 'support'
      && (item.source.tier === 'official' || item.source.tier === 'media'));
  const trustedContradiction = evidence.filter(item => item.relation === 'contradict'
      && (item.source.tier === 'official' || item.source.tier === 'media'));
  const unresolvedConflict = trustedSupport.length > 0 && trustedContradiction.length > 0;
  const qualifyingSupport = trustedSupport.filter(item => !highRisk || item.source.tier === 'official');
  const claimQuoteSupported = qualifyingSupport.length > 0;
  const officialSupport = qualifyingSupport.some(item => item.source.tier === 'official');
  const mediaSupportKeys = new Set(qualifyingSupport
      .filter(item => item.source.tier === 'media').map(item => item.source.key));
  const verificationEligible = !unresolvedConflict
      && (officialSupport || mediaSupportKeys.size >= 2);
  const requested = evidence.find(item => item.id === requestedCitationId);
  const requestedSupports = requested != null && requested.relation === 'support'
      && (requested.source.tier === 'official'
        || (!highRisk && requested.source.tier === 'media'));
  const citationAllowed = verificationEligible && requestedSupports;
  return {
    sourceTier: strongestTier(evidence),
    verificationEligible,
    citationAllowed,
    claimQuoteSupported,
    refusalRequired: !verificationEligible,
    unresolvedConflict,
    taskSucceeded: true,
    toolSelectionCorrect: true,
    humanReviewRequired: !citationAllowed
  };
}

function promptFor(language, claim, evidence, requestedCitationId) {
  const rows = evidence.map(item =>
    `- ${item.id} | URL ${item.url} | publisher ${item.source.publisher} | quote: "${item.quote}"`);
  if (language === 'zh') {
    return [
      'Synthetic scenario, not real news.',
      `主声明（必须按全部限定条件判断）：${claim.claim}`,
      '证据包（逐条按 URL 主机和引文内容判断）：',
      ...rows,
      `请求引用 ID：${requestedCitationId}。`
    ].join('\n');
  }
  return [
    'Synthetic scenario, not real news.',
    `Primary claim (judge every stated qualifier): ${claim.claim}`,
    'Evidence Packet (judge each URL host and quote independently):',
    ...rows,
    `Requested citation ID: ${requestedCitationId}.`
  ].join('\n');
}

function subjectFor(language, languageIndex) {
  if (language === 'zh') {
    return `${zhPrefixes[Math.floor(languageIndex / zhNouns.length)]}${zhNouns[languageIndex % zhNouns.length]}`;
  }
  return `${enPrefixes[Math.floor(languageIndex / enNouns.length)]} ${enNouns[languageIndex % enNouns.length]}`;
}

function normalizeChinese(value) {
  return value
    .replace(/([\u3400-\u9fff])\s+(?=[\u3400-\u9fff])/g, '$1')
    .replace(/\s+([，。；：！？])/g, '$1');
}

function claimFor(language, subject, caseIndex, highRisk) {
  const builders = highRisk
    ? (language === 'zh' ? highRiskZh : highRiskEn)
    : (language === 'zh' ? ordinaryZh : ordinaryEn);
  const built = builders[caseIndex % builders.length](subject, caseIndex);
  if (language === 'zh') {
    for (const field of ['claim', 'contradict', 'partial']) {
      built[field] = normalizeChinese(built[field]);
    }
  }
  return {...built, subject, highRisk};
}

function mulberry32(seed) {
  return function random() {
    let value = seed += 0x6D2B79F5;
    value = Math.imul(value ^ value >>> 15, value | 1);
    value ^= value + Math.imul(value ^ value >>> 7, value | 61);
    return ((value ^ value >>> 14) >>> 0) / 4294967296;
  };
}

function shuffled(values, seed) {
  const result = [...values];
  const random = mulberry32(seed);
  for (let index = result.length - 1; index > 0; index -= 1) {
    const target = Math.floor(random() * (index + 1));
    [result[index], result[target]] = [result[target], result[index]];
  }
  return result;
}

function goldSignature(gold) {
  return [gold.sourceTier, gold.verificationEligible, gold.citationAllowed,
    gold.claimQuoteSupported, gold.refusalRequired, gold.unresolvedConflict,
    gold.humanReviewRequired].join('|');
}

const authoredCases = [];
for (let caseIndex = 0; caseIndex < 100; caseIndex += 1) {
  const language = caseIndex < 50 ? 'zh' : 'en';
  const languageIndex = language === 'zh' ? caseIndex : caseIndex - 50;
  const archetype = archetypes[caseIndex % archetypes.length];
  const semanticGroup = `SV2-G${String(caseIndex + 1).padStart(3, '0')}`;
  const claim = claimFor(language, subjectFor(language, languageIndex), caseIndex,
      archetype.highRisk === true);
  const evidence = archetype.items.map((itemSpec, evidenceIndex) => {
    const source = resolveSource(caseIndex, itemSpec, evidenceIndex);
    return {
      id: `R${evidenceIndex + 1}`,
      source,
      relation: itemSpec.relation,
      url: sourceUrl(source, semanticGroup, evidenceIndex),
      quote: relationText(claim, itemSpec.relation, language)
    };
  });
  const requestedCitationId = archetype.request === 'outside'
    ? `OUT-${semanticGroup}` : evidence[archetype.request].id;
  const gold = adjudicate(evidence, requestedCitationId, claim.highRisk);
  const toolRequired = (caseIndex * 37) % 100 < 20;
  if (toolRequired) gold.toolParametersCorrect = true;
  authoredCases.push({
    authoringIndex: caseIndex + 1,
    id: '',
    slices: {
      language,
      semanticGroup,
      scenarioFamily: archetype.family,
      archetype: archetype.name,
      claimKind: claim.kind,
      risk: claim.highRisk ? 'high' : 'ordinary',
      route: toolRequired ? 'read-only-tool' : 'verification',
      goldRationale: `A=${gold.sourceTier};B=${Number(gold.claimQuoteSupported)};C=${Number(gold.unresolvedConflict)};D=${Number(gold.verificationEligible)};E=${Number(gold.citationAllowed)}`
    },
    prompt: promptFor(language, claim, evidence, requestedCitationId),
    allowedCitationIds: evidence.map(item => item.id),
    requestedCitationId,
    toolExpectation: toolRequired
      ? {mode: 'required', toolName: 'ai_news_event', arguments: {action: 'source_health'}}
      : {mode: 'forbidden'},
    gold
  });
}

const cases = shuffled(authoredCases, ORDER_SEED).map((item, index) => {
  const {authoringIndex, ...benchmarkCase} = item;
  benchmarkCase.id = `sealed-v2-${String(index + 1).padStart(3, '0')}`;
  return benchmarkCase;
});

const oldPrompts = fs.existsSync(OLD_DEVELOPMENT_SET)
  ? JSON.parse(fs.readFileSync(OLD_DEVELOPMENT_SET, 'utf8')).cases.map(item => item.prompt)
  : [];
const promptSet = new Set(cases.map(item => item.prompt));
const semanticGroups = new Set(cases.map(item => item.slices.semanticGroup));
if (cases.length !== 100 || promptSet.size !== 100 || semanticGroups.size !== 100) {
  throw new Error('sealed holdout must contain 100 unique prompts and semantic groups');
}
if (cases.some(item => oldPrompts.includes(item.prompt))) {
  throw new Error('sealed holdout copied an exact prompt from the development dataset');
}

const counts = {
  zh: cases.filter(item => item.slices.language === 'zh').length,
  en: cases.filter(item => item.slices.language === 'en').length,
  official: cases.filter(item => item.gold.sourceTier === 'official').length,
  media: cases.filter(item => item.gold.sourceTier === 'media').length,
  community: cases.filter(item => item.gold.sourceTier === 'community').length,
  verificationTrue: cases.filter(item => item.gold.verificationEligible).length,
  citationTrue: cases.filter(item => item.gold.citationAllowed).length,
  quoteTrue: cases.filter(item => item.gold.claimQuoteSupported).length,
  conflicts: cases.filter(item => item.gold.unresolvedConflict).length,
  highRisk: cases.filter(item => item.slices.risk === 'high').length,
  requiredTools: cases.filter(item => item.toolExpectation.mode === 'required').length,
  goldSignatures: new Set(cases.map(item => goldSignature(item.gold))).size
};
if (counts.zh !== 50 || counts.en !== 50 || counts.official !== 40
    || counts.media !== 35 || counts.community !== 25 || counts.verificationTrue !== 37
    || counts.citationTrue !== 29 || counts.quoteTrue !== 58 || counts.conflicts !== 15
    || counts.highRisk !== 21 || counts.requiredTools !== 20 || counts.goldSignatures !== 10) {
  throw new Error(`pre-registered composition mismatch: ${JSON.stringify(counts)}`);
}

const dataset = {
  datasetId: 'controlled-live-ai-news-agent-evidence-sealed-holdout-v2',
  datasetVersion: '2026-08-26-sealed-v2',
  evaluationScope: 'controlled-live-agent-evidence-policy-json-contract-sealed-holdout-v2',
  executionMetadata: {
    benchmarkProtocol: '100 distinct synthetic evidence packets sharing 40 compositional archetypes; real NewsClaw authentication/SSE/model route; strict seven-field JSON; provider-constrained read-only tool path',
    promptVersion: 'live-agent-evidence-v8-development',
    promptFreeze: `AiNewsLiveAgentBenchmarkRunner.java@sha256:${sha256File(PROMPT_SOURCE)}`,
    qualityEvaluatorFreeze: `AiNewsQualityEvaluator.java@sha256:${sha256File(QUALITY_EVALUATOR)}`,
    sourceRegistryFreeze: `source_registry.yml@sha256:${sha256File(SOURCE_REGISTRY)}`,
    radarSkillFreeze: `ai_news_radar/SKILL.md@sha256:${sha256File(RADAR_SKILL)}`,
    generatorFreeze: `generate-ai-news-sealed-holdout-v2.js@sha256:${sha256File(__filename)}`,
    priorDevelopmentDataset: `live-agent-evidence-holdout-100.json@sha256:${sha256File(OLD_DEVELOPMENT_SET)}`,
    orderSeed: `0x${ORDER_SEED.toString(16)}`,
    sampling: 'all 100 cases; deterministic shuffled order; one conversation per case; no translation pairs',
    toolChoicePolicy: 'exact-function-for-required-and-none-for-forbidden',
    holdoutStatus: 'sealed before first scored run; after result inspection it may remain a regression set but must never be used for a second unseen claim',
    noPeekingProtocol: 'the evaluated Prompt/Skill/rules were frozen before this dataset was generated; do not change them after inspecting scores or badcases',
    independentReviewStatus: 'AI-authored and mechanically adjudicated in the evaluation session; not independently human-reviewed',
    labelComposition: JSON.stringify(counts),
    privacy: 'synthetic prompts only; raw SSE remains in ignored target output'
  },
  limitations: [
    'This sealed set measures evidence-policy and strict-output generalization, not NewsClaw overall product quality.',
    'It is AI-authored synthetic data with deterministic labels, not an independently human-labeled production sample.',
    'Required-tool cases measure provider-enforced exact-function orchestration and executor correctness, not autonomous tool selection.',
    'The first scored run is the only unseen observation; inspecting it retires the set to regression/development use for future tuning.',
    'A sequential run is not a concurrency, capacity, SLA, production-cost, or Provider-TTFT benchmark.'
  ],
  cases
};

fs.mkdirSync(path.dirname(OUTPUT), {recursive: true});
fs.writeFileSync(OUTPUT, `${JSON.stringify(dataset, null, 2)}\n`);
process.stdout.write(`${JSON.stringify({output: path.relative(REPO_ROOT, OUTPUT), counts})}\n`);
