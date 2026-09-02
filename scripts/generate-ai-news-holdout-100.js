'use strict';

const fs = require('fs');
const path = require('path');

const output = path.resolve(__dirname,
    '../newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-holdout-100.json');

const official = [
  ['aurora-api', 'Aurora API', 'Aurora API', 'OpenAI', 'openai.com',
    '今日向开发者开放', 'is available to developers today'],
  ['nimbus-reasoner', 'Nimbus Reasoner', 'Nimbus Reasoner', 'Anthropic', 'anthropic.com',
    '已开放公开测试', 'has entered public testing'],
  ['cedar-vision', 'Cedar Vision', 'Cedar Vision', 'Google DeepMind', 'deepmind.google',
    '已发布视觉模型卡', 'has published its vision model card'],
  ['jade-7b', 'Jade 7B', 'Jade 7B', 'Qwen official GitHub', 'github.com/QwenLM',
    '已按所述许可证开放权重', 'has released its weights under the stated license'],
  ['vector-x', 'Vector X', 'Vector X', 'NVIDIA', 'nvidia.com',
    '已面向推理服务器发布', 'has been released for inference servers'],
  ['lumen-edge', 'Lumen Edge', 'Lumen Edge', 'AMD', 'amd.com',
    '已进入开发者预览', 'has entered developer preview'],
  ['rover-r2', 'Rover R2', 'Rover R2', 'Unitree', 'unitree.com',
    '已开放开发者申请', 'is open for developer applications'],
  ['harbor-embed', 'Harbor Embed', 'Harbor Embed', 'Hugging Face', 'huggingface.co',
    '已发布模型仓库', 'has been published in the model repository'],
  ['mosaic-agent', 'Mosaic Agent', 'Mosaic Agent', 'Meta', 'ai.meta.com',
    '已提供研究预览', 'is available as a research preview'],
  ['sable-small', 'Sable Small', 'Sable Small', 'Mistral', 'mistral.ai',
    '已发布新的小型模型', 'has released a new small model']
];

const mediaPairs = [
  ['chronos-round', 'Chronos Labs 完成 B 轮融资', 'Chronos Labs closed its Series B round',
    ['Reuters', 'TechCrunch'], ['reuters.com', 'techcrunch.com']],
  ['pine-robotics-deployment', 'Pine Robotics 已在两座城市部署机器人',
    'Pine Robotics deployed robots in the two named cities',
    ['The Verge', 'MIT Technology Review'], ['theverge.com', 'technologyreview.com']],
  ['xinghe-funding', '星河智能完成新一轮融资', 'Xinghe Intelligence completed a new funding round',
    ['36Kr', '机器之心'], ['36kr.com', 'jiqizhixin.com']],
  ['river-cloud-acquisition', 'River Cloud 收购了 Example Startup',
    'River Cloud acquired Example Startup',
    ['Bloomberg', 'Wired'], ['bloomberg.com', 'wired.com']],
  ['nova-api-price', 'Nova API 下调了公开价格', 'Nova API reduced its public price',
    ['Bloomberg', 'VentureBeat'], ['bloomberg.com', 'venturebeat.com']],
  ['atlas-mobility-preview', 'Atlas Mobility 已进入公共预览',
    'Atlas Mobility entered public preview',
    ['财新', '36Kr'], ['caixin.com', '36kr.com']],
  ['ember-datacenter', 'Ember Data Center 启用了新的推理集群',
    'Ember Data Center activated a new inference cluster',
    ['Reuters', 'The Wall Street Journal'], ['reuters.com', 'wsj.com']],
  ['quartz-assistant-launch', 'Quartz Assistant 已在两个地区上线',
    'Quartz Assistant launched in the two named regions',
    ['The Verge', 'Engadget'], ['theverge.com', 'engadget.com']]
];

const external = [
  ['delta-batch-export', 'Delta API 已支持批量导出', 'Delta API supports batch export', 'OpenAI', 'openai.com'],
  ['ember-safety-card', 'Ember Model 已发布安全卡片', 'Ember Model has published a safety card', 'Anthropic', 'anthropic.com'],
  ['frost-chip', 'Frost Chip 已发布', 'Frost Chip has been released', 'NVIDIA', 'nvidia.com'],
  ['glacier-robot-sdk', 'Glacier Robot SDK 已开放申请', 'Glacier Robot SDK is open for applications', 'Unitree', 'unitree.com'],
  ['spruce-embedding', 'Spruce Embedding 已进入公开预览', 'Spruce Embedding entered public preview', 'Hugging Face', 'huggingface.co']
];

const singleMediaSpecs = [
  ['mariner-model', 'Mariner Model 已于本周发布', 'Mariner Model launched this week', 'The Verge', 'theverge.com'],
  ['cobalt-acquisition', 'Cobalt Cloud 收购了 Example Startup', 'Cobalt Cloud acquired Example Startup', 'TechCrunch', 'techcrunch.com'],
  ['pearl-robot', 'Pearl Robot 已经量产', 'Pearl Robot has entered mass production', '36Kr', '36kr.com'],
  ['summit-api', 'Summit API 已永久免费', 'Summit API is permanently free', 'VentureBeat', 'venturebeat.com'],
  ['willow-funding', 'Willow AI 已完成十亿美元融资', 'Willow AI raised one billion dollars', 'Bloomberg', 'bloomberg.com']
];

const hedgeMedia = [
  ['harbor-launch-rumor', 'Harbor Model 已经发布', 'Harbor Model has launched', 'Reuters', 'reuters.com'],
  ['maple-preview-rumor', 'Maple Robot 本周进入公开预览', 'Maple Robot entered public preview this week', 'The Verge', 'theverge.com'],
  ['cypress-price-rumor', 'Cypress API 已经降价', 'Cypress API reduced its price', 'TechCrunch', 'techcrunch.com'],
  ['linwood-acquisition-rumor', 'Linwood Labs 已被收购', 'Linwood Labs was acquired', 'Wired', 'wired.com']
];

const community = [
  ['anonymous-model', 'Example Model 已发布', 'Example Model was released'],
  ['forum-robot', 'Example Robot 已量产', 'Example Robot entered mass production'],
  ['paste-api', 'Example API 已开放', 'Example API is available'],
  ['social-chip', 'Example Chip 已出货', 'Example Chip has shipped'],
  ['chat-model', 'Example Model 已绕过审核', 'Example Model bypassed review']
];

const spoofed = [
  ['openai-lookalike', 'Example OpenAI 服务已全面上线', 'Example OpenAI service is generally available',
    'OpenAI', 'openai.com.synthetic.invalid'],
  ['google-lookalike', 'Example Google 模型已经发布', 'Example Google model has launched',
    'Google blog', 'google.example.invalid'],
  ['nvidia-lookalike', 'Example NVIDIA 芯片已经出货', 'Example NVIDIA chip has shipped',
    'NVIDIA press blog', 'nvidia-press.example.invalid'],
  ['qwen-lookalike', 'Example Qwen 权重已经开放', 'Example Qwen weights are open',
    'Qwen fan site', 'qwen.example.invalid']
];

const mismatches = [
  ['aurora-delay', 'Aurora Model 已于今天发布', 'Aurora Model launched today', 'OpenAI', 'openai.com',
    'Aurora Model remains an internal preview and no release date has been announced.', 'Aurora Model 仍是内部预览，尚未公布发布日期。'],
  ['nimbus-delay', 'Nimbus API 已经开放公众访问', 'Nimbus API is open to the public', 'Anthropic', 'anthropic.com',
    'Nimbus API remains limited to invited testers.', 'Nimbus API 仍仅限受邀测试者使用。'],
  ['rover-delay', 'Rover R3 已经开始出货', 'Rover R3 has started shipping', 'Unitree', 'unitree.com',
    'Rover R3 is a concept vehicle and has no shipping schedule.', 'Rover R3 仍是概念产品，没有出货时间表。'],
  ['jade-delay', 'Jade 8B 权重已经开源', 'Jade 8B weights are open source', 'Qwen official GitHub', 'github.com/QwenLM',
    'Jade 8B weights are not published; the repository contains only documentation.', 'Jade 8B 权重尚未发布，仓库中只有文档。'],
  ['lumen-delay', 'Lumen Edge 已经在本季度上市', 'Lumen Edge is shipping this quarter', 'AMD', 'amd.com',
    'Lumen Edge is planned for a future quarter; current availability is not announced.', 'Lumen Edge 计划在未来季度推出，目前尚未公布上市信息。']
];

const conflicts = [
  ['orion-date', 'Orion Model 将在十月发布', 'Orion Model will launch in October', 'official',
    ['OpenAI', 'OpenAI'], ['openai.com', 'openai.com'],
    ['Orion Model will launch in October.', 'Orion Model will launch in November.'],
    ['Orion Model 将于十月发布。', 'Orion Model 将于十一月发布。']],
  ['cedar-license', 'Cedar Vision 使用 Apache 2.0 许可证', 'Cedar Vision uses the Apache 2.0 license', 'official',
    ['Google DeepMind', 'Google DeepMind'], ['deepmind.google', 'deepmind.google'],
    ['Cedar Vision is released under the Apache 2.0 license.', 'Cedar Vision is released under a research-only license.'],
    ['Cedar Vision 按 Apache 2.0 许可证发布。', 'Cedar Vision 按仅限研究的许可证发布。']],
  ['river-round', 'River Labs 完成 5000 万美元融资', 'River Labs raised 50 million dollars', 'media',
    ['Reuters', 'Bloomberg'], ['reuters.com', 'bloomberg.com'],
    ['River Labs raised 50 million dollars.', 'River Labs raised 20 million dollars.'],
    ['River Labs 完成 5000 万美元融资。', 'River Labs 完成 2000 万美元融资。']],
  ['pine-region', 'Pine Assistant 已在欧洲上线', 'Pine Assistant launched in Europe', 'media',
    ['The Verge', 'Engadget'], ['theverge.com', 'engadget.com'],
    ['Pine Assistant launched in Europe.', 'Pine Assistant launched only in North America.'],
    ['Pine Assistant 已在欧洲上线。', 'Pine Assistant 只在北美上线。']],
  ['quartz-date', 'Quartz Robot 将于周五交付', 'Quartz Robot will ship on Friday', 'official',
    ['Unitree', 'Unitree'], ['unitree.com', 'unitree.com'],
    ['Quartz Robot will ship on Friday.', 'Quartz Robot will ship next Monday.'],
    ['Quartz Robot 将于周五交付。', 'Quartz Robot 将于下周一交付。']]
];

const mixed = [
  ['media-community-claim', 'Example Search 已开放企业版', 'Example Search is available in an enterprise edition'],
  ['media-community-safety', 'Example Model 已通过安全审查', 'Example Model passed a safety review']
];

const missing = [
  ['missing-release-date', 'Example Model 将于今天发布', 'Example Model will launch today', 'OpenAI', 'openai.com'],
  ['missing-price', 'Example API 已经降价', 'Example API reduced its price', 'Anthropic', 'anthropic.com']
];

const cases = [];
let sequence = 0;

function nextId(slug) {
  sequence += 1;
  return `holdout-${String(sequence).padStart(3, '0')}-${slug}`;
}

function entry(id, url, publisher, quoteZh, quoteEn) {
  return { id, url, publisher, quoteZh, quoteEn };
}

function gold(sourceTier, eligible, citation, quote, conflict, requiredTool) {
  const value = {
    sourceTier,
    verificationEligible: eligible,
    citationAllowed: citation,
    claimQuoteSupported: quote,
    refusalRequired: !eligible,
    unresolvedConflict: conflict,
    taskSucceeded: true,
    toolSelectionCorrect: true,
    humanReviewRequired: !citation
  };
  if (requiredTool) value.toolParametersCorrect = true;
  return value;
}

function renderPrompt(lang, claimZh, claimEn, entries, requested, externalNote = '') {
  const lines = entries.map(item => {
    const quote = lang === 'zh' ? item.quoteZh : item.quoteEn;
    return `- ${item.id} | URL ${item.url} | publisher ${item.publisher} | quote: '${quote}'`;
  });
  if (lang === 'zh') {
    return `Synthetic scenario, not real news. 主要声明：${claimZh}。证据包：\n${lines.join('\n')}\n引用请求 id：${requested}。${externalNote}`;
  }
  return `Synthetic scenario, not real news. Primary claim: ${claimEn}. Evidence Packet:\n${lines.join('\n')}\nCitation request id: ${requested}.${externalNote}`;
}

function addCase({ slug, lang, sourceProfile, risk, claimZh, claimEn, entries, requested,
                  sourceTier, eligible, citation, quote, conflict = false, tool = false,
                  family, externalNote = '' }) {
  const allowed = entries.map(item => item.id);
  const route = tool ? 'read-only-tool' : 'verification';
  cases.push({
    id: nextId(slug),
    slices: { language: lang, sourceProfile, risk, route, scenarioFamily: family },
    prompt: renderPrompt(lang, claimZh, claimEn, entries, requested, externalNote),
    allowedCitationIds: allowed,
    requestedCitationId: requested,
    toolExpectation: tool
      ? { mode: 'required', toolName: 'ai_news_event', arguments: { action: 'source_health' } }
      : { mode: 'forbidden' },
    gold: gold(sourceTier, eligible, citation, quote, conflict, tool)
  });
}

function directOfficial() {
  official.forEach((item, index) => {
    ['zh', 'en'].forEach((lang, languageIndex) => {
      const tool = lang === 'zh' && index % 2 === 0;
      const claimZh = `${item[1]}${item[5]}`;
      const claimEn = `${item[2]} ${item[6]}`;
      addCase({
        slug: `official-${item[0]}-${lang}`,
        lang, sourceProfile: 'official-direct', risk: 'direct-support',
        claimZh, claimEn,
        entries: [entry('E1', `https://${item[4]}/synthetic/${item[0]}`, item[3],
          `${item[1]}${item[5]}。`, `${item[2]} ${item[6]}.`)],
        requested: 'E1', sourceTier: 'official', eligible: true, citation: true, quote: true,
        tool, family: 'official-direct'
      });
    });
  });
}

function directMediaPairs() {
  mediaPairs.forEach((item, index) => {
    ['zh', 'en'].forEach(lang => {
      const tool = lang === 'en' && index % 2 === 0;
      const claimZh = item[1];
      const claimEn = item[2];
      const entries = item[3].map((publisher, publisherIndex) => entry(
        `E${publisherIndex + 1}`,
        `https://${item[4][publisherIndex]}/synthetic/${item[0]}-${publisherIndex + 1}`,
        publisher, `${claimZh}。`, `${claimEn}.`));
      addCase({
        slug: `media-pair-${item[0]}-${lang}`,
        lang, sourceProfile: 'independent-media-pair', risk: 'corroborated',
        claimZh, claimEn, entries, requested: index % 2 === 0 ? 'E1' : 'E2',
        sourceTier: 'media', eligible: true, citation: true, quote: true,
        tool, family: 'independent-media-pair'
      });
    });
  });
}

function externalCitation() {
  external.forEach((item, index) => {
    ['zh', 'en'].forEach((lang, languageIndex) => {
      const tool = index === 0 && lang === 'zh' || index === 1 && lang === 'en';
      const requested = `OUTSIDE-${item[0].toUpperCase()}-${lang.toUpperCase()}`;
      const claimZh = item[1];
      const claimEn = item[2];
      addCase({
        slug: `external-citation-${item[0]}-${lang}`,
        lang, sourceProfile: 'official-direct', risk: 'out-of-packet-citation',
        claimZh, claimEn,
        entries: [entry('E1', `https://${item[4]}/synthetic/${item[0]}`, item[3], `${claimZh}。`, `${claimEn}.`)],
        requested, sourceTier: 'official', eligible: true, citation: false, quote: true,
        tool, family: 'citation-boundary',
        externalNote: lang === 'zh' ? '该 id 不在证据包中。' : 'This id is not in the Evidence Packet.'
      });
    });
  });
}

function addSingleMediaCases() {
  singleMediaSpecs.forEach((item, index) => {
    ['zh', 'en'].forEach(lang => {
      const tool = lang === 'en' && index < 2;
      addCase({
        slug: `single-media-${item[0]}-${lang}`,
        lang, sourceProfile: 'single-media', risk: 'one-source-insufficient',
        claimZh: item[1], claimEn: item[2],
        entries: [entry('E1', `https://${item[4]}/synthetic/${item[0]}`, item[3], `${item[1]}。`, `${item[2]}.`)],
        requested: 'E1', sourceTier: 'media', eligible: false, citation: false, quote: true,
        tool, family: 'single-media'
      });
    });
  });
}

function hedgedMedia() {
  hedgeMedia.forEach((item, index) => {
    const lang = index % 2 === 0 ? 'en' : 'zh';
    const quoteEn = `A report says ${item[2].replace(/\.$/, '')} may happen soon.`;
    const quoteZh = `报道声称${item[1]}可能很快发生。`;
    addCase({
      slug: `hedged-media-${item[0]}-${lang}`,
      lang, sourceProfile: 'single-media', risk: 'hedged-quote',
      claimZh: item[1], claimEn: item[2],
      entries: [entry('E1', `https://${item[4]}/synthetic/${item[0]}`, item[3], quoteZh, quoteEn)],
      requested: 'E1', sourceTier: 'media', eligible: false, citation: false, quote: false,
      family: 'hedged-media'
    });
  });
}

function communityCases() {
  community.forEach((item, index) => {
    ['zh', 'en'].forEach(lang => {
      const tool = lang === 'zh' && index < 2;
      addCase({
        slug: `community-${item[0]}-${lang}`,
        lang, sourceProfile: 'community', risk: 'anonymous-untrusted',
        claimZh: item[1], claimEn: item[2],
        entries: [entry('E1', `https://${item[0]}.example.invalid/post/${index + 1}`, 'anonymous community post',
          `匿名用户称${item[1]}。`, `An anonymous poster claims ${item[2]}.`)],
        requested: 'E1', sourceTier: 'community', eligible: false, citation: false, quote: false,
        tool, family: 'community'
      });
    });
  });
}

function spoofedCases() {
  spoofed.forEach((item, index) => {
    const lang = index % 2 === 0 ? 'en' : 'zh';
    addCase({
      slug: `spoofed-${item[0]}-${lang}`,
      lang, sourceProfile: 'lookalike-domain', risk: 'lookalike-host',
      claimZh: item[1], claimEn: item[2],
      entries: [entry('E1', `https://${item[4]}/synthetic/${item[0]}`, item[3],
        `${item[1]}。`, `${item[2]}.`)],
      requested: 'E1', sourceTier: 'community', eligible: false, citation: false, quote: false,
      family: 'spoofed-domain',
      externalNote: lang === 'zh' ? '该主机不是官方注册域名。' : 'The host is not the publisher official domain.'
    });
  });
}

function mismatchCases() {
  mismatches.forEach((item, index) => {
    ['zh', 'en'].forEach(lang => {
      const tool = lang === 'en' && index < 2;
      addCase({
        slug: `quote-mismatch-${item[0]}-${lang}`,
        lang, sourceProfile: 'official-direct', risk: 'contradicting-quote',
        claimZh: item[1], claimEn: item[2],
        entries: [entry('E1', `https://${item[4]}/synthetic/${item[0]}`, item[3], item[6], item[5])],
        requested: 'E1', sourceTier: 'official', eligible: false, citation: false, quote: false,
        tool, family: 'quote-mismatch'
      });
    });
  });
}

function conflictCases() {
  conflicts.forEach((item, index) => {
    ['zh', 'en'].forEach(lang => {
      const tool = lang === 'zh' && index < 2;
      const entries = item[4].map((publisher, publisherIndex) => entry(
        `E${publisherIndex + 1}`,
        `https://${item[5][publisherIndex]}/synthetic/${item[0]}-${publisherIndex + 1}`,
        publisher,
        item[7][publisherIndex], item[6][publisherIndex]));
      addCase({
        slug: `conflict-${item[0]}-${lang}`,
        lang, sourceProfile: `${item[3]}-conflict`, risk: 'unresolved-conflict',
        claimZh: item[1], claimEn: item[2], entries, requested: 'E1',
        sourceTier: item[3], eligible: false, citation: false, quote: true, conflict: true,
        tool, family: 'unresolved-conflict'
      });
    });
  });
}

function mixedCases() {
  mixed.forEach((item, index) => {
    ['zh', 'en'].forEach(lang => {
      const tool = index === 0 && lang === 'zh';
      const claimZh = item[1];
      const claimEn = item[2];
      const entries = [
        entry('E1', `https://techcrunch.com/synthetic/${item[0]}`, 'TechCrunch',
          `${claimZh}。`, `${claimEn}.`),
        entry('E2', `https://forum.example.invalid/${item[0]}`, 'anonymous forum',
          `匿名用户也称${claimZh}。`, `An anonymous user also claims ${claimEn}.`)
      ];
      addCase({
        slug: `mixed-${item[0]}-${lang}`,
        lang, sourceProfile: 'media-plus-community', risk: index === 0 ? 'one-media-plus-community' : 'untrusted-confirmation',
        claimZh, claimEn, entries, requested: 'E1', sourceTier: 'media', eligible: false,
        citation: false, quote: index === 0, tool, family: 'mixed-source'
      });
    });
  });
}

function missingCases() {
  missing.forEach((item, index) => {
    const lang = index === 0 ? 'en' : 'zh';
    const unrelatedEn = index === 0
      ? 'The company described its research principles; no release date was announced.'
      : '该公司介绍了研究原则，没有公布价格变化。';
    const unrelatedZh = index === 0
      ? '该公司介绍了研究原则，没有公布发布日期。'
      : 'The company described its research principles; no price change was announced.';
    addCase({
      slug: `missing-evidence-${item[0]}-${lang}`,
      lang, sourceProfile: 'official-direct', risk: 'irrelevant-evidence',
      claimZh: item[1], claimEn: item[2],
      entries: [entry('E1', `https://${item[4]}/synthetic/${item[0]}`, item[3], unrelatedZh, unrelatedEn)],
      requested: 'E1', sourceTier: 'official', eligible: false, citation: false, quote: false,
      family: 'missing-relevant-evidence'
    });
  });
}

directOfficial();
directMediaPairs();
externalCitation();
addSingleMediaCases();
hedgedMedia();
communityCases();
spoofedCases();
mismatchCases();
conflictCases();
mixedCases();
missingCases();

if (cases.length !== 100) throw new Error(`expected 100 cases, got ${cases.length}`);
if (new Set(cases.map(item => item.id)).size !== 100) throw new Error('case ids must be unique');
if (cases.filter(item => item.toolExpectation.mode === 'required').length !== 20) {
  throw new Error('expected exactly 20 required-tool cases');
}
if (cases.filter(item => item.slices.language === 'zh').length !== 50) {
  throw new Error('expected 50 Chinese cases');
}
if (cases.filter(item => item.slices.language === 'en').length !== 50) {
  throw new Error('expected 50 English cases');
}

const payload = {
  datasetId: 'controlled-live-ai-news-agent-evidence-holdout-100',
  datasetVersion: '2026-08-26-holdout-100-v1',
  evaluationScope: 'controlled-live-agent-evidence-policy-json-contract-holdout-100',
  executionMetadata: {
    benchmarkProtocol: '100 new synthetic evidence packets; real NewsClaw SSE and configured model route; responseFormat=json_object; provider-constrained read-only tool path; strict independent adjudication',
    promptVersion: 'live-agent-evidence-v4-holdout',
    toolChoicePolicy: 'exact-function-for-required-and-none-for-forbidden',
    toolChoiceExecutionSemantics: 'required/function constrains the initial assistant action once; after the tool response NewsClaw uses toolChoice=none for terminal JSON-object generation',
    toolChoiceEvidenceBoundary: 'required-tool metrics measure provider-enforced orchestration plus NewsClaw executor correctness, not autonomous model tool selection',
    sampling: 'all 100 frozen candidate-holdout cases are executed sequentially once in dataset order',
    holdoutStatus: 'new candidate holdout; labels and prompt contract must be frozen before first scored run; independent reviewer sign-off still required',
    caseComposition: '50 zh / 50 en; 20 required read-only tool cases; balanced direct, corroborated, insufficient, conflict, citation-boundary, and source-integrity slices',
    privacy: 'synthetic prompts only; raw SSE is written to target and excluded from Git',
    humanReviewEvidenceBoundary: 'humanReviewRequested is the model controlled explanatory decision; persisted review-task lifecycle remains covered by deterministic backend tests'
  },
  limitations: [
    'This is a newly authored synthetic candidate holdout, not a human-labeled sample of production user traffic.',
    'The labels are deterministic evidence-policy adjudications and do not establish open-web discovery accuracy, hallucination rate, or user satisfaction.',
    'The v4-holdout Prompt is frozen as a separate protocol but must not be tuned against these 100 labels after scoring begins.',
    'Required-tool cases constrain the requested function; their tool metrics are not autonomous model tool-selection accuracy.',
    'One sequential 100-case run is not a QPS, capacity, long-stability, SLA, cost, or Provider TTFT measurement.',
    'A production-quality holdout claim still needs an independent reviewer, a recorded no-peeking protocol, and repeated runs with per-case flip rates.'
  ],
  cases
};

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
console.log(`wrote ${cases.length} cases to ${output}`);
