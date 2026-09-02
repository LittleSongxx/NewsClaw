<template>
  <div class="ai-news-page">
    <header class="page-header">
      <div>
        <div class="eyebrow">AI NEWS OPS</div>
        <h1>AI 动态工作台</h1>
        <p>先看候选扫描的真实漏斗，再把人工采用的事件推进到证据核验、内容生产与交付。</p>
      </div>
      <div class="header-actions">
        <el-badge :value="clusterReviews.length" :hidden="clusterReviews.length === 0" class="cluster-review-badge">
          <el-button :loading="clusterReviewLoading" :icon="Document" @click="openClusterReviews">聚类复核</el-button>
        </el-badge>
        <el-button :loading="loading || candidateLoading" :icon="Refresh" @click="loadAll">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="showCreate = true">录入事件</el-button>
      </div>
    </header>

    <section class="pipeline-toolbar">
      <div>
        <h2>Shadow 候选流水线</h2>
        <span v-if="candidateSummary">{{ candidateSummary.run.topic }} · {{ formatTime(candidateSummary.run.startedAt) }}</span>
        <span v-else>显式开启流水线并产生扫描后，这里显示业务漏斗。</span>
      </div>
      <div class="pipeline-toolbar-actions">
        <el-select
          v-model="selectedScanRunId"
          :disabled="candidateRuns.length === 0"
          placeholder="暂无扫描"
          @change="onCandidateRunChange"
        >
          <el-option
            v-for="run in candidateRuns"
            :key="String(run.id)"
            :label="`${formatTime(run.startedAt)} · ${run.topic}`"
            :value="String(run.id)"
          />
        </el-select>
        <el-tag v-if="candidateSummary" :type="pipelineStatusType(candidateSummary.run.runStatus)">
          {{ pipelineStatusLabel(candidateSummary.run.runStatus) }}
        </el-tag>
      </div>
    </section>

    <section class="stage-strip" aria-label="候选业务记分卡">
      <div
        v-for="item in candidateMetrics"
        :key="item.key"
        class="stage-item"
        :class="`stage-${item.tone}`"
        :title="item.metric?.note"
      >
        <span class="stage-dot"></span>
        <span class="stage-copy">{{ item.label }}<small>{{ metricDetail(item.metric) }}</small></span>
        <strong>{{ metricValue(item.metric) }}</strong>
      </div>
    </section>

    <section class="event-panel candidate-panel">
      <div class="panel-heading">
        <div>
          <h2>候选池</h2>
          <span>{{ candidateTotal }} 条记录 · 全量观测先落库；采用后仍需人工 promotion，不会自动发布</span>
        </div>
        <div class="candidate-heading-actions">
          <el-select v-model="candidateReviewFilter" clearable placeholder="全部审核状态" @change="onCandidateFilterChange">
            <el-option label="待审核" value="PENDING" />
            <el-option label="已采用" value="ACCEPTED" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
          <el-button text :icon="Refresh" :loading="candidateLoading" @click="loadSelectedCandidateRun()">更新</el-button>
        </div>
      </div>

      <div v-if="candidateSummary?.providers.length" class="provider-yield">
        <el-tag v-for="provider in candidateSummary.providers" :key="provider.providerId" effect="plain">
          {{ provider.providerId }}：{{ provider.candidateCount }} 条 / 独有 {{ provider.marginalUniqueCount }} / 采用抓取 {{ provider.selectedCount }}
        </el-tag>
      </div>

      <el-table v-loading="candidateLoading" :data="candidates" row-key="id" class="candidate-table">
        <el-table-column label="候选" min-width="340">
          <template #default="{ row }">
            <div class="candidate-title-cell">
              <el-link :href="row.canonicalUrl" target="_blank" type="primary" @click.stop>
                {{ row.title || row.canonicalUrl }}
              </el-link>
              <p>{{ row.snippet || row.selectionReason || '暂无摘要' }}</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源观测" width="170">
          <template #default="{ row }">
            <div class="candidate-source-cell">
              <strong>{{ row.providerId }}</strong>
              <span>{{ row.queryLane }} · #{{ row.providerRank }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="选择" width="112">
          <template #default="{ row }">
            <el-tag size="small" :type="pipelineStatusType(row.selectionStatus)">{{ pipelineStatusLabel(row.selectionStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="抓取" width="112">
          <template #default="{ row }">
            <el-tag size="small" :type="pipelineStatusType(row.captureStatus)" :title="row.failureReason">
              {{ pipelineStatusLabel(row.captureStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="人工审核 / 晋级" width="300">
          <template #default="{ row }">
            <div class="candidate-review-cell">
              <el-tag size="small" :type="pipelineStatusType(row.reviewStatus)">{{ pipelineStatusLabel(row.reviewStatus) }}</el-tag>
              <el-button link type="success" :loading="candidateActionId === String(row.id)" @click.stop="reviewCandidate(row, 'ACCEPTED')">采用</el-button>
              <el-button link type="danger" :loading="candidateActionId === String(row.id)" @click.stop="reviewCandidate(row, 'REJECTED')">拒绝</el-button>
              <el-button
                v-if="canPromoteCandidate(row)"
                link
                type="primary"
                :loading="candidateActionId === String(row.id)"
                @click.stop="promoteCandidate(row)"
              >形成待核验事件</el-button>
              <el-tag v-else-if="row.eventId" size="small" type="success">事件 #{{ shortId(row.eventId) }}</el-tag>
              <small v-if="row.reviewReason" :title="row.reviewReason">{{ row.reviewReason }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最近发现" width="154">
          <template #default="{ row }"><span class="time-text">{{ formatTime(row.lastSeenAt) }}</span></template>
        </el-table-column>
        <template #empty>
          <el-empty :description="candidateRuns.length ? '该扫描暂无匹配候选' : '尚无候选扫描'" :image-size="70" />
        </template>
      </el-table>

      <div v-if="candidateTotal > candidatePageSize" class="pagination-wrap">
        <el-pagination
          v-model:current-page="candidatePage"
          :page-size="candidatePageSize"
          background
          layout="total, prev, pager, next"
          :total="candidateTotal"
          @current-change="loadSelectedCandidateRun()"
        />
      </div>
    </section>

    <section class="filter-bar">
      <el-input
        v-model="filters.keyword"
        clearable
        class="keyword-input"
        placeholder="搜索事件、摘要或实体"
        :prefix-icon="Search"
        @keyup.enter="resetAndLoad"
        @clear="resetAndLoad"
      />
      <el-select v-model="filters.category" clearable placeholder="全部分类" @change="resetAndLoad">
        <el-option v-for="item in categories" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="filters.status" clearable placeholder="全部状态" @change="resetAndLoad">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-button :icon="Search" @click="resetAndLoad">查询</el-button>
    </section>

    <section class="event-panel">
        <div class="panel-heading">
          <div>
            <h2>事件池</h2>
            <span>{{ total }} 条记录 · 官方来源优先核验</span>
          </div>
          <el-button text :icon="Refresh" @click="loadEvents">更新</el-button>
        </div>

        <el-table
          v-loading="loading"
          :data="events"
          row-key="id"
          class="event-table"
          :show-header="true"
          @row-click="openDetail"
        >
          <el-table-column label="事件" min-width="320">
            <template #default="{ row }">
              <div class="event-title-cell">
                <div class="event-title">{{ row.title }}</div>
                <div class="event-summary">{{ row.summary || '暂无摘要' }}</div>
                <div class="entity-list">
                  <el-tag v-for="entity in entitiesOf(row).slice(0, 4)" :key="entity" size="small" effect="plain">{{ entity }}</el-tag>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="118">
            <template #default="{ row }"><el-tag effect="plain">{{ categoryLabel(row.category) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="证据" width="138">
            <template #default="{ row }">
              <div class="evidence-cell">
                <span class="tier-dot" :class="tierClass(row)"></span>
                <span>{{ evidenceLabel(row) }}</span>
                <small>{{ confidenceLabel(row.confidence) }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="126">
            <template #default="{ row }"><el-tag :type="statusType(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="事件簇" width="132">
            <template #default="{ row }">
              <button v-if="row.clusterId" type="button" class="cluster-link" @click.stop="openClusterDetail(row.clusterId)">
                <span>#{{ shortId(row.clusterId) }} · {{ row.clusterMemberCount || 1 }} 条</span>
                <el-tag v-if="row.clusterReviewRequired" size="small" type="warning">待审</el-tag>
              </button>
              <span v-else class="time-text">未归簇</span>
            </template>
          </el-table-column>
          <el-table-column label="发现时间" width="154">
            <template #default="{ row }"><span class="time-text">{{ formatTime(row.discoveredAt || row.createTime) }}</span></template>
          </el-table-column>
          <el-table-column label="" width="46" fixed="right">
            <template #default="{ row }"><el-icon class="row-chevron"><ArrowRight /></el-icon></template>
          </el-table-column>
          <template #empty><el-empty description="暂无匹配事件" :image-size="80" /></template>
        </el-table>

        <div v-loading="loading" class="event-cards" aria-live="polite">
          <button
            v-for="item in events"
            :key="item.id"
            type="button"
            class="event-card"
            @click="openDetail(item)"
          >
            <div class="event-card-topline">
              <el-tag size="small" effect="plain">{{ categoryLabel(item.category) }}</el-tag>
              <el-tag size="small" :type="statusType(item.status)" effect="light">{{ statusLabel(item.status) }}</el-tag>
            </div>
            <strong class="event-card-title">{{ item.title }}</strong>
            <p>{{ item.summary || '暂无摘要' }}</p>
            <div v-if="entitiesOf(item).length" class="entity-list">
              <el-tag v-for="entity in entitiesOf(item).slice(0, 3)" :key="entity" size="small" effect="plain">{{ entity }}</el-tag>
            </div>
            <button v-if="item.clusterId" type="button" class="cluster-link mobile" @click.stop="openClusterDetail(item.clusterId)">簇 #{{ shortId(item.clusterId) }} · {{ item.clusterMemberCount || 1 }} 条</button>
            <div class="event-card-meta">
              <span><i class="tier-dot" :class="tierClass(item)"></i>{{ evidenceLabel(item) }} · {{ confidenceLabel(item.confidence) }}</span>
              <time>{{ formatTime(item.discoveredAt || item.createTime) }}</time>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </button>
          <el-empty v-if="!loading && events.length === 0" description="暂无匹配事件" :image-size="70" />
        </div>

        <div class="pagination-wrap">
          <el-pagination
            class="desktop-pagination"
            v-model:current-page="page"
            v-model:page-size="pageSize"
            background
            layout="total, sizes, prev, pager, next"
            :total="total"
            :page-sizes="[10, 20, 50]"
            @current-change="loadEvents"
            @size-change="onSizeChange"
          />
          <el-pagination
            class="mobile-pagination"
            v-model:current-page="page"
            :page-size="pageSize"
            small
            background
            layout="prev, pager, next"
            :pager-count="5"
            :total="total"
            @current-change="loadEvents"
          />
        </div>
    </section>

    <el-drawer v-model="detailVisible" title="事件详情" size="min(720px, 96vw)" destroy-on-close>
      <template v-if="detailLoading"><el-skeleton :rows="8" animated /></template>
      <template v-else-if="detail">
        <div class="detail-head">
          <div class="detail-kicker"><el-tag :type="statusType(detail.event.status)">{{ statusLabel(detail.event.status) }}</el-tag><span>{{ categoryLabel(detail.event.category) }}</span></div>
          <h2>{{ detail.event.title }}</h2>
          <p>{{ detail.event.summary || '暂无摘要' }}</p>
          <div class="detail-meta">
            <span>发现于 {{ formatTime(detail.event.discoveredAt || detail.event.createTime) }}</span>
            <span>来源发布 {{ formatTime(detail.event.sourcePublishedAt) }}</span>
            <span>证据质量 {{ confidenceLabel(detail.event.rankingScore) }}</span>
            <span>核验置信度 {{ confidenceLabel(detail.event.confidence) }}</span>
          </div>
        </div>

        <el-alert
          v-if="detail.event.reviewRequired"
          class="review-alert"
          type="warning"
          :closable="false"
          show-icon
          title="当前事件仍有确定性复核门禁"
          :description="reviewReasonText(detail.event.reviewReasons)"
        />

        <button
          v-if="detail.event.clusterId"
          type="button"
          class="cluster-summary-card"
          @click="openClusterDetail(detail.event.clusterId)"
        >
          <div>
            <strong>事件身份簇 #{{ shortId(detail.event.clusterId) }}</strong>
            <span>版本 {{ shortId(detail.event.clusterVersionId) }} · {{ detail.event.clusterMemberCount || 1 }} 条来源观察</span>
          </div>
          <div class="cluster-summary-meta">
            <el-tag v-if="detail.event.clusterReviewRequired" type="warning" size="small">低置信待审</el-tag>
            <span>{{ clusterOriginLabel(detail.event.clusterAssignmentOrigin) }} · {{ confidenceLabel(detail.event.clusterAssignmentScore) }}</span>
            <el-icon><ArrowRight /></el-icon>
          </div>
        </button>

        <div class="detail-section">
          <div class="section-title"><h3>来源证据</h3><span>{{ detail.evidence.length }} 条</span></div>
          <div v-if="detail.evidence.length" class="evidence-list">
            <article v-for="item in detail.evidence" :key="item.id" class="evidence-card">
              <div class="evidence-card-head">
                <el-tag size="small" :type="tierTagType(item.sourceTier)">{{ tierLabel(item.sourceTier) }}</el-tag>
                <el-tag size="small" effect="plain" :type="relationTagType(item.semanticRelation)">{{ relationLabel(item.semanticRelation) }}</el-tag>
                <el-tag size="small" effect="plain" :type="attestationTagType(item.relationOrigin)">{{ attestationLabel(item.relationOrigin) }}</el-tag>
                <el-tag v-if="item.verified" size="small" type="success" effect="plain">已核验</el-tag>
                <span>{{ confidenceLabel(item.relationConfidence ?? item.confidence) }}</span>
              </div>
              <el-link :href="item.sourceUrl" target="_blank" type="primary" class="source-link">{{ item.sourceTitle || item.sourceUrl }}</el-link>
              <p class="claim">{{ item.claim }}</p>
              <blockquote v-if="item.quote">“{{ item.quote }}”</blockquote>
              <div v-if="item.relationReviewNote" class="relation-review-note">人工结论：{{ item.relationReviewNote }}</div>
              <el-button
                v-if="canReviewEvidence(detail.event)"
                class="relation-review-button"
                size="small"
                plain
                @click="openRelationReview(item)"
              >{{ relationAttested(item.relationOrigin) ? '更正关系' : '人工复核关系' }}</el-button>
            </article>
          </div>
          <el-empty v-else description="暂无证据" :image-size="70" />
        </div>

        <div v-if="detail.captureAttempts?.length" class="detail-section">
          <div class="section-title"><h3>官方抓取记录</h3><span>{{ detail.captureAttempts.length }} 次</span></div>
          <div class="capture-list">
            <article v-for="attempt in detail.captureAttempts" :key="attempt.id" class="capture-row">
              <div class="capture-status-line">
                <el-tag size="small" :type="captureStatusType(attempt.captureStatus)">{{ captureStatusLabel(attempt.captureStatus) }}</el-tag>
                <span v-if="attempt.httpStatus">HTTP {{ attempt.httpStatus }}</span>
                <time>{{ formatTime(attempt.attemptedAt) }}</time>
              </div>
              <el-link :href="attempt.finalUrl || attempt.sourceUrl" target="_blank" type="primary" class="source-link">{{ attempt.finalUrl || attempt.sourceUrl }}</el-link>
              <p v-if="attempt.captureError" class="capture-error">{{ attempt.captureError }}</p>
            </article>
          </div>
        </div>

        <div v-if="detail.event.claimsJson || detail.event.conflictsJson" class="detail-section claims-section">
          <div v-if="claimsOf(detail.event).length"><div class="section-title"><h3>关键声明</h3></div><ul><li v-for="claim in claimsOf(detail.event)" :key="claim">{{ claim }}</li></ul></div>
          <div v-if="conflictsOf(detail.event).length" class="conflict-box"><el-icon><WarningFilled /></el-icon><div><strong>来源冲突</strong><p v-for="item in conflictsOf(detail.event)" :key="item">{{ item }}</p></div></div>
        </div>

        <div class="detail-section relation-section">
          <div class="section-title"><h3>闭环关联</h3></div>
          <div class="relation-grid">
            <button class="relation-item" :disabled="!detail.event.wikiPageId" @click="goWiki(detail.event.wikiPageId, detail.event.wikiKbId, detail.event.wikiSlug)"><el-icon><Document /></el-icon><span>Wiki 证据页</span><strong>{{ detail.event.wikiPageId || '未关联' }}</strong></button>
            <button class="relation-item" :disabled="!detail.event.teamRunId" @click="goRun(detail.event.teamRunId)"><el-icon><Operation /></el-icon><span>Team Run</span><strong>{{ detail.event.teamRunId || '未启动' }}</strong></button>
            <button class="relation-item" :disabled="!detail.event.gzhContentItemId" @click="goCalendar(detail.event.gzhContentItemId)"><el-icon><ChatDotRound /></el-icon><span>公众号</span><strong>{{ detail.event.gzhContentItemId || '未生成' }}</strong></button>
            <button class="relation-item" :disabled="!detail.event.xhsContentItemId" @click="goCalendar(detail.event.xhsContentItemId)"><el-icon><Picture /></el-icon><span>小红书</span><strong>{{ detail.event.xhsContentItemId || '未生成' }}</strong></button>
          </div>
        </div>

        <div class="drawer-actions">
          <el-button v-if="canVerify(detail.event)" type="success" :icon="CircleCheck" :loading="actionLoading" @click="verifyEvent">核验通过</el-button>
          <el-button v-if="canVerify(detail.event)" type="warning" plain :icon="WarningFilled" :loading="actionLoading" @click="markConflict">标记冲突</el-button>
          <el-button v-if="detail.event.status === 'verified' && detail.event.reviewRequired" type="warning" :icon="CircleCheck" @click="openRiskReview">完成人工风险复核</el-button>
          <el-button v-if="detail.event.status === 'verified'" type="primary" :icon="Operation" :disabled="detail.event.reviewRequired" :loading="actionLoading" @click="produceEvent">开始创作</el-button>
          <el-button v-if="canMarkPublished(detail.event)" type="success" plain :icon="Promotion" :loading="actionLoading" @click="markPublished">确认已交付</el-button>
          <el-button v-if="!['archived', 'rejected', 'published'].includes(detail.event.status)" text type="danger" :icon="Delete" :loading="actionLoading" @click="dismissEvent">忽略</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="showCreate" title="录入 AI 动态事件" width="560px" destroy-on-close>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="事件标题" prop="title"><el-input v-model="createForm.title" maxlength="512" show-word-limit /></el-form-item>
        <el-form-item label="分类" prop="category"><el-select v-model="createForm.category" class="full-width"><el-option v-for="item in categories" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="摘要"><el-input v-model="createForm.summary" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="来源 URL" prop="sourceUrl"><el-input v-model="createForm.sourceUrl" placeholder="https://..." /></el-form-item>
        <el-form-item label="来源等级"><el-radio-group v-model="createForm.sourceTier"><el-radio value="official">官方</el-radio><el-radio value="media">媒体</el-radio><el-radio value="community">社区</el-radio></el-radio-group></el-form-item>
        <el-form-item label="事实声明" prop="claim"><el-input v-model="createForm.claim" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="showCreate = false">取消</el-button><el-button type="primary" :loading="createLoading" @click="createEvent">保存候选</el-button></template>
    </el-dialog>

    <el-dialog v-model="relationDialogVisible" title="人工复核 Claim ↔ Quote" width="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="语义关系">
          <el-select v-model="relationReviewForm.semanticRelation" class="full-width">
            <el-option v-for="item in relationOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="判断置信度">
          <el-slider v-model="relationReviewForm.confidence" :min="0" :max="1" :step="0.05" show-input />
        </el-form-item>
        <el-form-item label="复核说明（必填）">
          <el-input v-model="relationReviewForm.note" type="textarea" :rows="3" maxlength="2000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relationDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewLoading" :disabled="!relationReviewForm.note.trim()" @click="submitRelationReview">记录人工结论</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="riskReviewDialogVisible" title="完成高风险人工复核" width="560px" destroy-on-close>
      <p class="dialog-hint">该操作会记录当前登录用户和复核说明；证据或声明改变后，门禁会自动重新打开。</p>
      <el-input v-model="riskReviewNote" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="说明已核查的风险点、原始来源与结论" />
      <template #footer>
        <el-button @click="riskReviewDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewLoading" :disabled="!riskReviewNote.trim()" @click="submitRiskReview">确认完成复核</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="clusterReviewVisible" title="低置信事件聚类复核" width="min(980px, 96vw)" append-to-body>
      <p class="dialog-hint">候选在人工批准前始终保持为独立事件簇；批准会新增不可变合并版本，拒绝则确认保持分离。</p>
      <el-table v-loading="clusterReviewLoading" :data="clusterReviews" row-key="id" max-height="520">
        <el-table-column label="待审事件" min-width="150">
          <template #default="{ row }"><span class="mono-value">#{{ shortId(row.eventId) }}</span></template>
        </el-table-column>
        <el-table-column label="当前独立簇" min-width="150">
          <template #default="{ row }"><el-button link type="primary" @click="openClusterDetail(row.sourceClusterId)">#{{ shortId(row.sourceClusterId) }}</el-button></template>
        </el-table-column>
        <el-table-column label="建议目标簇" min-width="150">
          <template #default="{ row }"><el-button link type="primary" @click="openClusterDetail(row.candidateClusterId)">#{{ shortId(row.candidateClusterId) }}</el-button></template>
        </el-table-column>
        <el-table-column label="链接分" width="100">
          <template #default="{ row }">{{ confidenceLabel(row.score) }}</template>
        </el-table-column>
        <el-table-column label="自动门槛" width="100">
          <template #default="{ row }">{{ confidenceLabel(row.decisionThreshold) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="176" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" :loading="clusterActionLoading" @click="resolveCluster(row, 'approve')">批准合并</el-button>
            <el-button size="small" :loading="clusterActionLoading" @click="resolveCluster(row, 'reject')">保持分离</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无待处理聚类建议" :image-size="70" /></template>
      </el-table>
    </el-dialog>

    <el-dialog v-model="clusterDetailVisible" title="事件簇版本与谱系" width="min(1040px, 96vw)" append-to-body>
      <div v-loading="clusterDetailLoading" class="cluster-detail-dialog">
        <template v-if="clusterDetail">
          <div class="cluster-detail-head">
            <div>
              <span class="mono-value">簇 #{{ shortId(clusterDetail.cluster.id) }}</span>
              <h3>{{ clusterDetail.currentVersion.canonicalTitle }}</h3>
            </div>
            <div class="cluster-detail-tags">
              <el-tag :type="clusterDetail.cluster.status === 'active' ? 'success' : 'info'">{{ clusterDetail.cluster.status }}</el-tag>
              <el-tag effect="plain">v{{ clusterDetail.currentVersion.versionNo }}</el-tag>
              <el-tag v-if="clusterDetail.cluster.pendingReviewCount" type="warning">{{ clusterDetail.cluster.pendingReviewCount }} 项待审</el-tag>
            </div>
          </div>
          <div class="cluster-provenance">
            <span>{{ clusterDetail.currentVersion.algorithmName }}@{{ clusterDetail.currentVersion.algorithmVersion }}</span>
            <span>{{ clusterDetail.currentVersion.featureVersion }}</span>
            <code>{{ clusterDetail.currentVersion.configHash }}</code>
          </div>

          <div class="section-title cluster-section-title"><h3>当前成员</h3><span>勾选真子集可人工纠错拆分</span></div>
          <el-table
            :data="clusterDetail.currentEvents"
            row-key="id"
            max-height="300"
            @selection-change="onClusterMemberSelection"
          >
            <el-table-column v-if="clusterDetail.currentEvents.length > 1" type="selection" width="44" />
            <el-table-column prop="title" label="事件" min-width="310" />
            <el-table-column label="来源时间" width="155"><template #default="{ row }">{{ formatTime(row.sourcePublishedAt || row.discoveredAt) }}</template></el-table-column>
            <el-table-column label="归簇依据" width="150"><template #default="{ row }">{{ clusterOriginLabel(membershipFor(row.id)?.assignmentOrigin) }}</template></el-table-column>
            <el-table-column label="分数" width="80"><template #default="{ row }">{{ confidenceLabel(membershipFor(row.id)?.membershipScore) }}</template></el-table-column>
          </el-table>
          <div v-if="clusterDetail.currentEvents.length > 1" class="cluster-member-actions">
            <el-button
              type="warning"
              plain
              :loading="clusterActionLoading"
              :disabled="selectedClusterEventIds.length === 0 || selectedClusterEventIds.length >= clusterDetail.currentEvents.length"
              @click="splitSelectedCluster"
            >拆分所选 {{ selectedClusterEventIds.length || '' }}</el-button>
          </div>

          <div class="cluster-history-grid">
            <section>
              <div class="section-title cluster-section-title"><h3>不可变版本</h3><span>{{ clusterDetail.versions.length }} 个</span></div>
              <div class="cluster-history-list">
                <div v-for="version in clusterDetail.versions" :key="version.id" class="cluster-history-item">
                  <strong>v{{ version.versionNo }} · {{ version.changeType }}</strong>
                  <span>{{ version.memberCount }} 条 · {{ formatTime(version.createTime) }} · {{ version.createdBy || 'system' }}</span>
                  <small v-if="version.changeReason">{{ version.changeReason }}</small>
                </div>
              </div>
            </section>
            <section>
              <div class="section-title cluster-section-title"><h3>合并/拆分谱系</h3><span>{{ clusterDetail.lineage.length }} 条</span></div>
              <div v-if="clusterDetail.lineage.length" class="cluster-history-list">
                <div v-for="edge in clusterDetail.lineage" :key="edge.id" class="cluster-history-item">
                  <strong>{{ edge.operationType }} · #{{ shortId(edge.fromClusterId) }} → #{{ shortId(edge.toClusterId) }}</strong>
                  <span>{{ formatTime(edge.createTime) }} · {{ edge.reviewer || 'system' }}</span>
                  <small v-if="edge.reason">{{ edge.reason }}</small>
                </div>
              </div>
              <el-empty v-else description="尚无人工合并或拆分" :image-size="60" />
            </section>
          </div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ArrowRight, ChatDotRound, CircleCheck, Delete, Document, Operation, Picture,
  Plus, Promotion, Refresh, Search, WarningFilled,
} from '@element-plus/icons-vue'
import {
  aiNewsApi,
  type AiNewsCandidate,
  type AiNewsCandidateMetric,
  type AiNewsCandidateRunSummary,
  type AiNewsEvidence,
  type AiNewsEvent,
  type AiNewsEventClusterDetail,
  type AiNewsEventClusterMember,
  type AiNewsEventClusterReview,
  type AiNewsEventDetail,
  type AiNewsScanRun,
} from '@/api'

const router = useRouter()
const loading = ref(false)
const candidateLoading = ref(false)
const detailLoading = ref(false)
const actionLoading = ref(false)
const createLoading = ref(false)
const detailVisible = ref(false)
const showCreate = ref(false)
const relationDialogVisible = ref(false)
const riskReviewDialogVisible = ref(false)
const clusterReviewVisible = ref(false)
const clusterDetailVisible = ref(false)
const reviewLoading = ref(false)
const clusterReviewLoading = ref(false)
const clusterDetailLoading = ref(false)
const clusterActionLoading = ref(false)
const candidateActionId = ref('')
const riskReviewNote = ref('')
const detail = ref<AiNewsEventDetail | null>(null)
const clusterDetail = ref<AiNewsEventClusterDetail | null>(null)
const clusterReviews = ref<AiNewsEventClusterReview[]>([])
const selectedClusterEventIds = ref<Array<string | number>>([])
const events = ref<AiNewsEvent[]>([])
const candidates = ref<AiNewsCandidate[]>([])
const candidateRuns = ref<AiNewsScanRun[]>([])
const candidateSummary = ref<AiNewsCandidateRunSummary | null>(null)
const selectedScanRunId = ref('')
const total = ref(0)
const candidateTotal = ref(0)
const page = ref(1)
const pageSize = ref(20)
const candidatePage = ref(1)
const candidatePageSize = 20
const candidateReviewFilter = ref('')
const createFormRef = ref<FormInstance>()
const relationReviewForm = reactive({
  evidenceId: '' as string | number,
  semanticRelation: 'entails' as 'entails' | 'contradicts' | 'partial' | 'unrelated' | 'hedged',
  confidence: 1,
  note: '',
})

const filters = reactive({ keyword: '', category: '', status: '' })
const createForm = reactive({ title: '', category: 'model', summary: '', sourceUrl: '', sourceTier: 'official', claim: '' })
const categories = [
  { value: 'model', label: '模型与研究' }, { value: 'product', label: 'AI 产品' },
  { value: 'open_source', label: '开源生态' }, { value: 'security', label: '安全与风险' },
  { value: 'infrastructure', label: '芯片与基础设施' }, { value: 'partnership', label: '合作与并购' },
  { value: 'funding', label: '融资' }, { value: 'robotics', label: '机器人与具身智能' },
  { value: 'industry', label: '产业动态' }, { value: 'policy', label: '政策与治理' },
]
const relationOptions = [
  { value: 'entails', label: '完整支持（entails）' },
  { value: 'partial', label: '部分支持（partial）' },
  { value: 'contradicts', label: '明确矛盾（contradicts）' },
  { value: 'hedged', label: '带保留/不确定（hedged）' },
  { value: 'unrelated', label: '无关（unrelated）' },
] as const
const statusOptions = [
  { value: 'candidate', label: '候选' }, { value: 'researching', label: '核验中' }, { value: 'verified', label: '已核验' },
  { value: 'conflicted', label: '有冲突' }, { value: 'in_production', label: '生产中' }, { value: 'published', label: '已交付' },
  { value: 'rejected', label: '已忽略' }, { value: 'archived', label: '已归档' },
]
const candidateMetrics = computed(() => {
  const scorecard = candidateSummary.value?.scorecard
  return [
    { key: 'recall', label: '找得全', tone: 'candidate', metric: scorecard?.candidateRecall },
    { key: 'precision', label: '找得准', tone: 'verified', metric: scorecard?.relevantPrecision },
    { key: 'capture', label: '抓得到', tone: 'production', metric: scorecard?.usableCaptureRate },
    { key: 'acceptance', label: '用得住', tone: 'published', metric: scorecard?.reviewerAcceptance },
  ]
})
const createRules: FormRules = {
  title: [{ required: true, message: '请输入事件标题', trigger: 'blur' }],
  sourceUrl: [{ required: true, message: '请输入来源 URL', trigger: 'blur' }, { type: 'url', message: '请输入有效 URL', trigger: 'blur' }],
  claim: [{ required: true, message: '请输入事实声明', trigger: 'blur' }],
}

function responseData(res: any) { return res?.data ?? res }
function metricValue(metric?: AiNewsCandidateMetric) {
  return metric?.rate == null ? '待评测' : `${Math.round(metric.rate * 100)}%`
}
function metricDetail(metric?: AiNewsCandidateMetric) {
  return metric?.denominator ? `${metric.numerator}/${metric.denominator}` : '暂无样本'
}
function categoryLabel(value?: string) { return categories.find((item) => item.value === value)?.label || value || '未分类' }
function statusLabel(value?: string) { return statusOptions.find((item) => item.value === value)?.label || value || '未知' }
function statusType(value?: string): 'success' | 'warning' | 'danger' | 'info' | undefined {
  if (value === 'verified' || value === 'published') return 'success'
  if (value === 'conflicted' || value === 'researching') return 'warning'
  if (value === 'rejected') return 'danger'
  if (value === 'in_production') return undefined
  return 'info'
}
function pipelineStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    RUNNING: '扫描中', CANDIDATES_PERSISTED: '候选已落库', CAPTURE_PENDING: '等待抓取', COMPLETED: '已完成', FAILED: '失败',
    SELECTED: '已选取', NOT_SELECTED: '未选取', PENDING: '待处理', RETRYABLE: '待重试',
    SUCCESS: '成功', SKIPPED: '跳过', ACCEPTED: '已采用', REJECTED: '已拒绝',
  }
  return labels[value || ''] || value || '未知'
}
function pipelineStatusType(value?: string): 'success' | 'warning' | 'danger' | 'info' | undefined {
  if (['COMPLETED', 'SELECTED', 'SUCCESS', 'ACCEPTED'].includes(value || '')) return 'success'
  if (['FAILED', 'REJECTED'].includes(value || '')) return 'danger'
  if (['RUNNING', 'CANDIDATES_PERSISTED', 'CAPTURE_PENDING', 'PENDING', 'RETRYABLE'].includes(value || '')) return 'warning'
  return 'info'
}
function captureStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    success: '抓取成功', blocked: '站点阻断', not_found: '页面不存在', timeout: '请求超时',
    empty_content: '正文为空', redirect_rejected: '重定向拒绝', network_error: '网络错误',
  }
  return labels[value || ''] || value || '未知'
}
function captureStatusType(value?: string): 'success' | 'warning' | 'danger' | 'info' | undefined {
  if (value === 'success') return 'success'
  if (value === 'blocked' || value === 'timeout' || value === 'redirect_rejected') return 'warning'
  if (value === 'not_found' || value === 'empty_content' || value === 'network_error') return 'danger'
  return 'info'
}
function tierTagType(value?: string): 'success' | 'warning' | 'info' | undefined { return value === 'official' ? 'success' : value === 'community' ? 'info' : 'warning' }
function tierLabel(value?: string) { return value === 'official' ? '官方来源' : value === 'community' ? '社区来源' : '媒体来源' }
function relationLabel(value?: string) {
  return relationOptions.find((item) => item.value === value)?.label || (value === 'unknown' ? '待判断' : value || '待判断')
}
function relationTagType(value?: string): 'success' | 'warning' | 'danger' | 'info' | undefined {
  if (value === 'entails') return 'success'
  if (value === 'contradicts') return 'danger'
  if (value === 'partial' || value === 'hedged') return 'warning'
  return 'info'
}
function relationAttested(value?: string) { return value === 'HUMAN' || value === 'DETERMINISTIC_EXTRACTIVE' }
function attestationLabel(value?: string) {
  if (value === 'HUMAN') return '人工已确认'
  if (value === 'DETERMINISTIC_EXTRACTIVE') return '逐字确定性'
  if (value === 'MODEL') return '仅模型判断'
  return '未确认'
}
function attestationTagType(value?: string): 'success' | 'warning' | 'info' | undefined {
  return relationAttested(value) ? 'success' : value === 'MODEL' ? 'warning' : 'info'
}
function reviewReasonText(values?: string[]) {
  const labels: Record<string, string> = {
    UNRESOLVED_CONFLICT: '存在未解决冲突', VERIFICATION_NOT_ELIGIBLE: '尚不具备核验资格',
    LOW_TRUST_OR_UNREGISTERED_SOURCE: '来源未注册或可信度不足', MISSING_EVIDENCE_QUOTE: '缺少逐字引文',
    MISSING_SEMANTIC_ASSESSMENT: '缺少语义关系判断', UNATTESTED_SEMANTIC_ASSESSMENT: '语义关系仅由模型判断',
    CLAIM_NOT_SUPPORTED: '引文未完整支持声明', TRUSTED_SOURCE_CONTRADICTION: '可信来源存在矛盾',
    HIGH_RISK_CLAIM_REQUIRES_REVIEW: '高风险声明必须人工复核', UNCAPTURED_SOURCE: '来源未完成只读抓取绑定',
    MISSING_SOURCE_TIMESTAMP: '缺少可靠来源发布时间', UNCAPTURED_OFFICIAL_SOURCE: '官方来源未完成抓取',
    OFFICIAL_CAPTURE_FAILED_OR_BLOCKED: '官方抓取失败或被阻断',
  }
  return (values || []).map((item) => labels[item] || item).join('；') || '存在待处理风险，请逐条核查证据。'
}
// Element Plus exposes table slot rows as an opaque DefaultRow type even
// though this table is populated exclusively with AiNewsEvent API records.
function tierClass(row: any) {
  if (row.status === 'conflicted') return 'conflict'
  if (row.primaryEvidenceTier === 'official') return 'official'
  if (row.primaryEvidenceTier === 'community') return 'community'
  return 'media'
}
function evidenceLabel(row: any) {
  if (row.status === 'conflicted') return '需处理冲突'
  // Evidence is its own state. Do not infer it from a downstream lifecycle.
  if (Number(row.verifiedEvidenceCount || 0) > 0) return '已核验'
  if (Number(row.evidenceCount || 0) === 0) return '无证据'
  return '待核验'
}
function confidenceLabel(value?: number | null) { return `${Math.round((Number(value) || 0) * 100)}%` }
function formatTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '—' }
function shortId(value?: string | number | null) {
  if (value === undefined || value === null) return '—'
  const text = String(value)
  return text.length > 8 ? text.slice(-8) : text
}
function clusterOriginLabel(value?: string | null) {
  const labels: Record<string, string> = {
    SINGLETON: '首发独立簇', SINGLETON_REVIEW: '低置信独立簇', EXACT_URL: '规范 URL',
    EXACT_EVENT_KEY: '事件键', AUTO_RULES: '自动规则', MANUAL_MERGE: '人工合并',
    MANUAL_SPLIT: '人工拆分',
  }
  return labels[value || ''] || value || '未知依据'
}
function parseJson(value?: string): string[] { try { const parsed = value ? JSON.parse(value) : []; return Array.isArray(parsed) ? parsed.filter((item) => typeof item === 'string') : [] } catch { return [] } }
// Element Plus table slots expose a generic DefaultRow type; the API payload
// is still normalized as AiNewsEvent at the load boundary.
function entitiesOf(row: any) { return parseJson(row.entitiesJson) }
function claimsOf(row: any) { return parseJson(row.claimsJson) }
function conflictsOf(row: any) { return parseJson(row.conflictsJson) }
function canVerify(row: any) { return ['candidate', 'researching', 'conflicted'].includes(row.status) }
function canReviewEvidence(row: any) { return ['candidate', 'researching', 'verified', 'conflicted'].includes(row.status) }
function canMarkPublished(row: any) {
  return row.status === 'in_production'
    && (!!row.gzhContentItemId || !!row.xhsContentItemId)
}

function clearCandidateRun() {
  candidateSummary.value = null
  candidates.value = []
  candidateTotal.value = 0
}
async function fetchSelectedCandidateRun() {
  if (!selectedScanRunId.value) {
    clearCandidateRun()
    return
  }
  const [summaryResponse, candidatesResponse]: any[] = await Promise.all([
    aiNewsApi.getCandidateRun(selectedScanRunId.value),
    aiNewsApi.listCandidates({
      page: candidatePage.value,
      size: candidatePageSize,
      scanRunId: selectedScanRunId.value,
      reviewStatus: candidateReviewFilter.value || undefined,
    }),
  ])
  candidateSummary.value = responseData(summaryResponse)
  const pageData = responseData(candidatesResponse)
  candidates.value = pageData?.records || pageData?.list || []
  candidateTotal.value = Number(pageData?.total ?? candidates.value.length)
}
async function loadCandidatePipeline(showError = true) {
  candidateLoading.value = true
  try {
    const pageData = responseData(await aiNewsApi.listCandidateRuns({ page: 1, size: 20 }))
    candidateRuns.value = pageData?.records || pageData?.list || []
    if (!candidateRuns.value.some((run) => String(run.id) === selectedScanRunId.value)) {
      selectedScanRunId.value = candidateRuns.value[0] ? String(candidateRuns.value[0].id) : ''
      candidatePage.value = 1
    }
    await fetchSelectedCandidateRun()
  } catch (error: any) {
    candidateRuns.value = []
    selectedScanRunId.value = ''
    clearCandidateRun()
    if (showError) ElMessage.error(errorMessage(error, '加载候选流水线失败'))
  } finally { candidateLoading.value = false }
}
async function loadSelectedCandidateRun(showError = true) {
  candidateLoading.value = true
  try {
    await fetchSelectedCandidateRun()
  } catch (error: any) {
    clearCandidateRun()
    if (showError) ElMessage.error(errorMessage(error, '加载候选扫描失败'))
  } finally { candidateLoading.value = false }
}
function onCandidateRunChange() {
  candidatePage.value = 1
  loadSelectedCandidateRun()
}
function onCandidateFilterChange() {
  candidatePage.value = 1
  loadSelectedCandidateRun()
}
function canPromoteCandidate(row: AiNewsCandidate) {
  return !row.eventId
    && row.selectionStatus === 'SELECTED'
    && row.reviewStatus === 'ACCEPTED'
    && row.captureStatus === 'SUCCESS'
}
function candidateCategory(row: AiNewsCandidate) {
  const lane = (row.queryLane || '').toLowerCase()
  if (lane.includes('funding')) return 'funding'
  if (lane.includes('robot')) return 'robotics'
  if (lane.includes('infrastructure')) return 'infrastructure'
  if (lane.includes('security')) return 'security'
  if (lane.includes('model')) return 'model'
  if (lane.includes('product')) return 'product'
  return 'industry'
}
async function reviewCandidate(row: AiNewsCandidate, decision: 'ACCEPTED' | 'REJECTED') {
  const accepted = decision === 'ACCEPTED'
  try {
    const { value } = await ElMessageBox.prompt(
      accepted ? '说明为何该候选值得进入后续事件审核。' : '说明拒绝原因，便于分析搜索与排序坏例。',
      accepted ? '采用候选' : '拒绝候选',
      {
        confirmButtonText: accepted ? '确认采用' : '确认拒绝',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: accepted ? '相关事件、重要性或参考事件编号' : '例如：旧资料、教程、纯营销、重复事件',
        inputValue: row.reviewReason || '',
        inputValidator: (input) => input.trim().length >= 4 || '审核说明至少 4 个字符',
      },
    )
    candidateActionId.value = String(row.id)
    await aiNewsApi.reviewCandidate(row.id, decision, value.trim())
    ElMessage.success(accepted ? '候选已采用' : '候选已拒绝')
    await fetchSelectedCandidateRun()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error, '候选审核失败'))
  } finally { candidateActionId.value = '' }
}

async function promoteCandidate(row: AiNewsCandidate) {
  try {
    if (!row.captureId) {
      ElMessage.error('候选缺少成功 capture，无法读取原文')
      return
    }
    const captureResponse: any = await aiNewsApi.readSourceCapture(row.captureId, 0)
    const capturePage = responseData(captureResponse)
    const capturedText = String(capturePage?.content || capturePage?.excerpt || '').trim()
    if (!capturedText) {
      ElMessage.error('成功 capture 没有可引用正文')
      return
    }
    const { value: claim } = await ElMessageBox.prompt(
      '只写一个可由该来源直接支持的原子事实（不超过 512 字符）。',
      '形成待核验事件 · 事实',
      {
        confirmButtonText: '下一步填写原文引用',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputValue: row.title || '',
        inputValidator: (input) => input.trim().length >= 8 && input.trim().length <= 512 || '事实需为 8-512 个字符',
      },
    )
    const { value: quote } = await ElMessageBox.prompt(
      `必须逐字引用下方 capture 正文；摘要或改写会被拒绝。\n\n${capturedText.slice(0, 1200)}`,
      '形成待核验事件 · 原文引用',
      {
        confirmButtonText: '提交 promotion',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '粘贴来源原文片段',
        inputValue: capturedText.slice(0, 500),
        inputValidator: (input) => input.trim().length >= 12 || '原文引用至少 12 个字符',
      },
    )
    candidateActionId.value = String(row.id)
    await aiNewsApi.promoteCandidate(row.id, {
      claim: claim.trim(),
      quote: quote.trim(),
      category: candidateCategory(row),
      entities: row.sourceKey ? [row.sourceKey] : [],
      semanticRelation: 'unknown',
    })
    ElMessage.success('已形成待核验事件；仍需事件证据核验和发布审批')
    await Promise.all([fetchSelectedCandidateRun(), loadEvents()])
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error, '候选 promotion 失败'))
  } finally { candidateActionId.value = '' }
}

async function loadEvents() {
  loading.value = true
  try {
    const res: any = await aiNewsApi.list({ page: page.value, size: pageSize.value, keyword: filters.keyword || undefined, category: filters.category || undefined, status: filters.status || undefined })
    const data = responseData(res)
    events.value = data?.records || data?.list || []
    total.value = Number(data?.total ?? events.value.length)
  } catch (error: any) {
    events.value = []
    total.value = 0
    ElMessage.error(errorMessage(error, '加载 AI 动态失败'))
  } finally { loading.value = false }
}
async function loadAll() {
  await Promise.all([loadCandidatePipeline(), loadEvents(), loadClusterReviews(false)])
}
function resetAndLoad() { page.value = 1; loadEvents() }
function onSizeChange() { page.value = 1; loadEvents() }
async function openDetail(row: AiNewsEvent) {
  detailVisible.value = true; detailLoading.value = true; detail.value = null
  try { detail.value = responseData(await aiNewsApi.get(row.id)) } catch (error: any) { ElMessage.error(errorMessage(error, '加载事件详情失败')); detailVisible.value = false } finally { detailLoading.value = false }
}
async function loadClusterReviews(showError = true) {
  clusterReviewLoading.value = true
  try {
    clusterReviews.value = responseData(await aiNewsApi.listClusterReviews('PENDING', 100)) || []
  } catch (error: any) {
    clusterReviews.value = []
    if (showError) ElMessage.error(errorMessage(error, '加载聚类复核队列失败'))
  } finally { clusterReviewLoading.value = false }
}
async function openClusterReviews() {
  clusterReviewVisible.value = true
  await loadClusterReviews()
}
async function openClusterDetail(id: string | number) {
  clusterDetailVisible.value = true
  clusterDetailLoading.value = true
  clusterDetail.value = null
  selectedClusterEventIds.value = []
  try {
    clusterDetail.value = responseData(await aiNewsApi.getCluster(id))
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '加载事件簇详情失败'))
    clusterDetailVisible.value = false
  } finally { clusterDetailLoading.value = false }
}
function membershipFor(eventId: string | number): AiNewsEventClusterMember | undefined {
  return clusterDetail.value?.currentMemberships.find((item) => String(item.eventId) === String(eventId))
}
function onClusterMemberSelection(rows: AiNewsEvent[]) {
  selectedClusterEventIds.value = rows.map((row) => row.id)
}
async function resolveCluster(row: AiNewsEventClusterReview, decision: 'approve' | 'reject') {
  const approving = decision === 'approve'
  try {
    const { value } = await ElMessageBox.prompt(
      approving
        ? '批准后会新增合并版本并保留原簇谱系；请写明判定依据。'
        : '该候选会保持为独立事件簇；请写明区分依据。',
      approving ? '批准事件簇合并' : '确认保持分离',
      {
        confirmButtonText: approving ? '批准合并' : '保持分离',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '依据涉及的主体、动作、产品/版本和时间窗口',
        inputValidator: (input) => input.trim().length >= 8 || '复核说明至少 8 个字符',
      },
    )
    clusterActionLoading.value = true
    await aiNewsApi.resolveClusterReview(row.id, decision, value.trim())
    ElMessage.success(approving ? '已合并并记录不可变谱系' : '已确认保持分离')
    await Promise.all([loadClusterReviews(false), loadEvents()])
    if (detail.value) detail.value = responseData(await aiNewsApi.get(detail.value.event.id))
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error, '聚类复核失败'))
  } finally { clusterActionLoading.value = false }
}
async function splitSelectedCluster() {
  if (!clusterDetail.value || selectedClusterEventIds.value.length === 0
    || selectedClusterEventIds.value.length >= clusterDetail.value.currentEvents.length) return
  try {
    const { value } = await ElMessageBox.prompt(
      `将 ${selectedClusterEventIds.value.length} 条观察拆成新簇，原簇和新簇都会保留版本与谱系。`,
      '纠错拆分事件簇',
      {
        confirmButtonText: '确认拆分', cancelButtonText: '取消', inputType: 'textarea',
        inputPlaceholder: '说明为何这些观察属于另一个原子事件',
        inputValidator: (input) => input.trim().length >= 8 || '纠错说明至少 8 个字符',
      },
    )
    clusterActionLoading.value = true
    const sourceClusterId = clusterDetail.value.cluster.id
    clusterDetail.value = responseData(await aiNewsApi.splitCluster(
      sourceClusterId, selectedClusterEventIds.value, value.trim()))
    selectedClusterEventIds.value = []
    ElMessage.success('已拆分并记录不可变谱系')
    await Promise.all([loadClusterReviews(false), loadEvents()])
    if (detail.value) detail.value = responseData(await aiNewsApi.get(detail.value.event.id))
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error, '事件簇拆分失败'))
  } finally { clusterActionLoading.value = false }
}
async function updateDetail(action: () => Promise<any>, success: string) {
  if (!detail.value) return
  const eventId = detail.value.event.id
  actionLoading.value = true
  try {
    await action()
    detail.value = responseData(await aiNewsApi.get(eventId))
    ElMessage.success(success)
    await loadEvents()
  } catch (error: any) { ElMessage.error(errorMessage(error, '操作失败')) } finally { actionLoading.value = false }
}
function verifyEvent() { updateDetail(() => aiNewsApi.verify(detail.value!.event.id), '事件已通过核验') }
function markConflict() { updateDetail(() => aiNewsApi.verify(detail.value!.event.id, { verdict: 'conflicted' }), '已标记来源冲突') }
function produceEvent() { updateDetail(() => aiNewsApi.produce(detail.value!.event.id, true), '已进入内容生产并启动 Team Run') }
function markPublished() { updateDetail(() => aiNewsApi.markPublished(detail.value!.event.id), '已记录内容交付') }
function dismissEvent() { updateDetail(() => aiNewsApi.dismiss(detail.value!.event.id), '事件已忽略') }
function openRelationReview(item: AiNewsEvidence) {
  relationReviewForm.evidenceId = item.id
  relationReviewForm.semanticRelation = relationOptions.some((option) => option.value === item.semanticRelation)
    ? item.semanticRelation as typeof relationReviewForm.semanticRelation : 'entails'
  relationReviewForm.confidence = Number(item.relationConfidence ?? 1)
  relationReviewForm.note = item.relationReviewNote || ''
  relationDialogVisible.value = true
}
async function submitRelationReview() {
  if (!detail.value || !relationReviewForm.note.trim()) return
  reviewLoading.value = true
  try {
    await aiNewsApi.reviewEvidenceRelation(detail.value.event.id, relationReviewForm.evidenceId, {
      semanticRelation: relationReviewForm.semanticRelation,
      confidence: relationReviewForm.confidence,
      note: relationReviewForm.note.trim(),
    })
    detail.value = responseData(await aiNewsApi.get(detail.value.event.id))
    relationDialogVisible.value = false
    ElMessage.success('人工语义关系已记录')
    await loadEvents()
  } catch (error: any) { ElMessage.error(errorMessage(error, '记录人工复核失败')) } finally { reviewLoading.value = false }
}
function openRiskReview() { riskReviewNote.value = ''; riskReviewDialogVisible.value = true }
async function submitRiskReview() {
  if (!detail.value || !riskReviewNote.value.trim()) return
  reviewLoading.value = true
  try {
    await aiNewsApi.resolveReview(detail.value.event.id, riskReviewNote.value.trim())
    detail.value = responseData(await aiNewsApi.get(detail.value.event.id))
    riskReviewDialogVisible.value = false
    ElMessage.success('高风险人工复核已完成')
    await loadEvents()
  } catch (error: any) { ElMessage.error(errorMessage(error, '完成人工复核失败')) } finally { reviewLoading.value = false }
}
async function createEvent() {
  if (!createFormRef.value) return
  try { await createFormRef.value.validate() } catch { return }
  createLoading.value = true
  try {
    await aiNewsApi.upsert({ title: createForm.title, category: createForm.category, summary: createForm.summary, evidence: [{ sourceUrl: createForm.sourceUrl, sourceTier: createForm.sourceTier, claim: createForm.claim }] })
    ElMessage.success('候选事件已保存'); showCreate.value = false; Object.assign(createForm, { title: '', summary: '', sourceUrl: '', sourceTier: 'official', claim: '' }); await loadEvents()
  } catch (error: any) { ElMessage.error(errorMessage(error, '保存候选失败')) } finally { createLoading.value = false }
}
function errorMessage(error: any, fallback: string) { return error?.response?.data?.msg || error?.response?.data?.message || error?.message || fallback }
function goWiki(id?: string | number | null, kbId?: string | number | null, slug?: string | null) {
  if (!id) return
  if (kbId) {
    router.push({ path: `/wiki/${String(kbId)}`, query: slug ? { slug } : undefined })
    return
  }
  // Older events may only have the page id. Open the Wiki library instead of
  // treating that page id as a knowledge-base id and navigating to a dead URL.
  router.push('/wiki')
}
function goRun(id?: string | number | null) { if (id) router.push({ path: '/agents', query: { view: 'live', runId: String(id) } }) }
function goCalendar(id?: string | number | null) { if (id) router.push({ path: '/content-calendar', query: { contentId: String(id) } }) }

onMounted(loadAll)
</script>

<style scoped>
.ai-news-page { min-height: 100%; padding: 30px 36px 48px; color: var(--mc-text-primary); }
.page-header { max-width: 1440px; margin: 0 auto 24px; display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; }
.eyebrow { color: var(--mc-accent); font-size: 11px; font-weight: 800; letter-spacing: 0; margin-bottom: 8px; }
.page-header h1 { margin: 0; font-size: 29px; line-height: 1.2; font-weight: 760; }
.page-header p { margin: 8px 0 0; color: var(--mc-text-tertiary); font-size: 13px; line-height: 1.6; }
.header-actions { display: flex; gap: 10px; flex-shrink: 0; }
.cluster-review-badge { display: inline-flex; }
.pipeline-toolbar, .stage-strip, .filter-bar, .event-panel { max-width: 1440px; margin-left: auto; margin-right: auto; }
.pipeline-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 10px; }
.pipeline-toolbar h2 { margin: 0; font-size: 16px; }.pipeline-toolbar span { color: var(--mc-text-tertiary); font-size: 12px; }
.pipeline-toolbar-actions { display: flex; align-items: center; gap: 9px; }.pipeline-toolbar-actions .el-select { width: 330px; }
.stage-strip { display: grid; grid-template-columns: repeat(4, 1fr); background: var(--mc-panel-top); border: 1px solid var(--mc-border); border-radius: 8px; overflow: hidden; margin-bottom: 14px; box-shadow: none; }
.stage-item { display: flex; align-items: center; gap: 9px; padding: 14px 18px; border-right: 1px solid var(--mc-border-light); font-size: 13px; color: var(--mc-text-secondary); }
.stage-item:last-child { border-right: 0; }
.stage-item strong { margin-left: auto; color: var(--mc-text-primary); font-size: 18px; }
.stage-copy { display: grid; gap: 2px; }.stage-copy small { color: var(--mc-text-tertiary); font-size: 10px; }
.stage-dot, .tier-dot { width: 8px; height: 8px; border-radius: 50%; background: #94a3b8; flex: 0 0 auto; }
.stage-candidate .stage-dot { background: #d97706; }.stage-verified .stage-dot { background: #16836d; }.stage-production .stage-dot { background: #2563eb; }.stage-published .stage-dot { background: #0f766e; }
.filter-bar { display: flex; gap: 10px; padding: 12px; background: var(--mc-panel-top); border: 1px solid var(--mc-border); border-radius: 8px; box-shadow: none; margin-bottom: 16px; }
.keyword-input { max-width: 390px; flex: 1; }.filter-bar .el-select { width: 170px; }.filter-bar .el-button { margin-left: auto; }
.event-panel { background: var(--mc-panel-top); border: 1px solid var(--mc-border); border-radius: 8px; box-shadow: none; overflow: hidden; }
.candidate-panel { margin-bottom: 16px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px 14px; border-bottom: 1px solid var(--mc-border-light); }.panel-heading.compact { padding-bottom: 17px; }
.panel-heading h2 { margin: 0; font-size: 16px; font-weight: 700; }.panel-heading span { display: block; color: var(--mc-text-tertiary); font-size: 12px; margin-top: 4px; }
.candidate-heading-actions { display: flex; align-items: center; gap: 8px; }.candidate-heading-actions .el-select { width: 150px; }
.provider-yield { display: flex; gap: 6px; flex-wrap: wrap; padding: 10px 20px; border-bottom: 1px solid var(--mc-border-light); background: var(--mc-bg-muted); }
.candidate-table { width: 100%; --el-table-header-bg-color: var(--mc-bg-muted); --el-table-tr-bg-color: var(--mc-panel-top); --el-table-bg-color: var(--mc-panel-top); --el-table-border-color: var(--mc-border-light); }
.candidate-title-cell { display: grid; gap: 5px; padding: 4px 0; }.candidate-title-cell p { max-width: 520px; margin: 0; overflow: hidden; color: var(--mc-text-tertiary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.candidate-source-cell { display: grid; gap: 3px; }.candidate-source-cell strong { font-size: 12px; }.candidate-source-cell span { color: var(--mc-text-tertiary); font-size: 11px; }
.candidate-review-cell { display: flex; align-items: center; gap: 5px; flex-wrap: wrap; }.candidate-review-cell .el-button { margin-left: 0; }.candidate-review-cell small { flex: 1 0 100%; overflow: hidden; color: var(--mc-text-tertiary); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.event-table { width: 100%; cursor: pointer; --el-table-header-bg-color: var(--mc-bg-muted); --el-table-tr-bg-color: var(--mc-panel-top); --el-table-bg-color: var(--mc-panel-top); --el-table-border-color: var(--mc-border-light); --el-table-row-hover-bg-color: var(--mc-primary-bg); }.event-title-cell { padding: 5px 0; }.event-title { font-weight: 650; line-height: 1.35; }.event-summary { color: var(--mc-text-tertiary); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 500px; margin-top: 4px; }.entity-list { display: flex; gap: 4px; margin-top: 7px; }.entity-list .el-tag { border-radius: 4px; }.evidence-cell { display: grid; grid-template-columns: 9px 1fr; align-items: center; gap: 5px; font-size: 12px; }.evidence-cell small { grid-column: 2; color: var(--mc-text-tertiary); }.tier-dot.official { background: #16836d; }.tier-dot.media { background: #d97706; }.tier-dot.community { background: #2563eb; }.tier-dot.conflict { background: var(--mc-danger); }.time-text { color: var(--mc-text-tertiary); font-size: 12px; }.row-chevron { color: var(--mc-text-tertiary); }
.cluster-link { display: inline-flex; align-items: center; gap: 5px; max-width: 100%; padding: 0; border: 0; color: var(--mc-primary); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }
.cluster-link:hover { text-decoration: underline; }.cluster-link.mobile { display: none; margin-top: 9px; }
:deep(.event-table .el-table__header th.el-table__cell) { color: var(--mc-text-secondary); font-size: 12px; font-weight: 700; }
:deep(.event-table .el-table__row:hover > td.el-table__cell) { background: var(--mc-primary-bg); }
.pagination-wrap { display: flex; justify-content: flex-end; padding: 14px 18px; border-top: 1px solid var(--mc-border-light); }
.event-cards, .mobile-pagination { display: none; }
.detail-head h2 { margin: 12px 0 7px; font-size: 23px; line-height: 1.35; }.detail-head p { margin: 0; color: var(--mc-text-secondary); line-height: 1.65; }.detail-kicker, .detail-meta { display: flex; align-items: center; gap: 10px; color: var(--mc-text-tertiary); font-size: 12px; }.detail-meta { margin-top: 12px; }.detail-section { margin-top: 26px; }.section-title { display: flex; align-items: center; gap: 10px; margin-bottom: 11px; }.section-title h3 { margin: 0; font-size: 14px; }.section-title span { color: var(--mc-text-tertiary); font-size: 12px; }.evidence-list { display: grid; gap: 9px; }.evidence-card { padding: 13px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg-muted); }.evidence-card-head { display: flex; align-items: center; gap: 7px; margin-bottom: 8px; }.evidence-card-head span { margin-left: auto; color: var(--mc-text-tertiary); font-size: 11px; }.source-link { max-width: 100%; font-size: 13px; }.claim { margin: 8px 0 0; line-height: 1.55; font-size: 13px; }.evidence-card blockquote { margin: 8px 0 0; padding-left: 10px; border-left: 2px solid var(--mc-border); color: var(--mc-text-tertiary); font-size: 12px; line-height: 1.55; }.capture-list { display: grid; gap: 8px; }.capture-row { padding: 11px 12px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg-muted); }.capture-status-line { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; color: var(--mc-text-tertiary); font-size: 11px; }.capture-status-line time { margin-left: auto; }.capture-error { margin: 7px 0 0; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.5; }.claims-section ul { margin: 0; padding-left: 18px; color: var(--mc-text-secondary); font-size: 13px; line-height: 1.7; }.conflict-box { display: flex; gap: 9px; padding: 12px; color: var(--mc-danger); background: var(--mc-danger-bg); border: 1px solid var(--mc-danger-border); border-radius: 8px; font-size: 12px; }.conflict-box p { margin: 4px 0 0; }.relation-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 9px; }.relation-item { display: grid; grid-template-columns: 20px 1fr; text-align: left; gap: 2px 7px; padding: 11px; border: 1px solid var(--mc-border); border-radius: 8px; background: transparent; color: var(--mc-text-secondary); cursor: pointer; }.relation-item:hover:not(:disabled) { border-color: var(--mc-primary); background: var(--mc-bg-muted); }.relation-item:disabled { opacity: .52; cursor: not-allowed; }.relation-item .el-icon { grid-row: 1 / span 2; margin-top: 2px; }.relation-item span { font-size: 11px; }.relation-item strong { color: var(--mc-text-primary); font-size: 12px; }.drawer-actions { display: flex; gap: 9px; flex-wrap: wrap; margin-top: 28px; padding-top: 16px; border-top: 1px solid var(--mc-border-light); }.full-width { width: 100%; }
.detail-meta, .evidence-card-head { flex-wrap: wrap; }
.review-alert { margin-top: 18px; }
.cluster-summary-card { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 18px; padding: 13px 14px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg-muted); color: inherit; text-align: left; cursor: pointer; }
.cluster-summary-card:hover { border-color: var(--mc-primary); }.cluster-summary-card > div:first-child { display: grid; gap: 4px; }.cluster-summary-card strong { font-size: 13px; }.cluster-summary-card span { color: var(--mc-text-tertiary); font-size: 11px; }.cluster-summary-meta { display: flex; align-items: center; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
.mono-value { color: var(--mc-text-secondary); font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 12px; }
.cluster-detail-dialog { min-height: 160px; }.cluster-detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }.cluster-detail-head h3 { margin: 5px 0 0; font-size: 18px; }.cluster-detail-tags { display: flex; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }.cluster-provenance { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 13px 0 20px; color: var(--mc-text-tertiary); font-size: 11px; }.cluster-provenance code { max-width: 100%; padding: 3px 6px; overflow: hidden; text-overflow: ellipsis; border-radius: 4px; background: var(--mc-bg-muted); }.cluster-section-title { margin-top: 19px; }.cluster-member-actions { display: flex; justify-content: flex-end; margin-top: 10px; }.cluster-history-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }.cluster-history-list { display: grid; gap: 7px; max-height: 260px; overflow: auto; }.cluster-history-item { display: grid; gap: 3px; padding: 10px 11px; border: 1px solid var(--mc-border-light); border-radius: 7px; background: var(--mc-bg-muted); }.cluster-history-item strong { font-size: 12px; }.cluster-history-item span, .cluster-history-item small { color: var(--mc-text-tertiary); font-size: 11px; line-height: 1.45; }
.relation-review-note { margin-top: 8px; color: var(--mc-text-secondary); font-size: 12px; }
.relation-review-button { margin-top: 10px; }
.dialog-hint { margin: 0 0 12px; color: var(--mc-text-secondary); font-size: 13px; line-height: 1.6; }
@media (max-width: 680px) {
  .ai-news-page { padding: 18px 12px 30px; overflow-x: clip; }
  .page-header { display: block; }
  .page-header h1 { font-size: 23px; }
  .page-header p { max-width: 34em; line-height: 1.55; }
  .header-actions { margin-top: 14px; }
  .header-actions { flex-wrap: wrap; }.cluster-review-badge { flex: 1; }.cluster-review-badge :deep(.el-button) { width: 100%; }
  .header-actions .el-button { flex: 1; margin-left: 0; }
  .pipeline-toolbar { align-items: flex-start; flex-direction: column; }.pipeline-toolbar-actions { width: 100%; }.pipeline-toolbar-actions .el-select { min-width: 0; flex: 1; width: auto; }
  .stage-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .stage-item { padding: 12px 13px; }
  .stage-item:nth-child(2) { border-right: 0; }
  .stage-item:nth-child(-n+2) { border-bottom: 1px solid var(--mc-border-light); }
  .filter-bar { flex-wrap: wrap; padding: 11px; }
  .keyword-input { max-width: none; flex: 1 0 100%; }
  .filter-bar .el-select { width: auto; flex: 1 1 calc(50% - 5px); min-width: 0; }
  .filter-bar .el-button { margin-left: 0; flex: 1 0 100%; }
  .panel-heading { padding: 15px 14px 12px; }
  .candidate-panel .panel-heading { align-items: flex-start; gap: 12px; flex-direction: column; }.candidate-heading-actions { width: 100%; }.candidate-heading-actions .el-select { flex: 1; width: auto; }.provider-yield { padding: 9px 14px; }
  .candidate-panel { overflow-x: auto; }.candidate-panel .panel-heading, .candidate-panel .provider-yield, .candidate-panel .pagination-wrap { min-width: 620px; }.candidate-table { min-width: 1060px; }
  .event-table { display: none; }
  .event-cards { display: block; min-height: 120px; }
  .event-card { width: 100%; display: block; padding: 15px 14px; text-align: left; color: inherit; background: transparent; border: 0; border-bottom: 1px solid var(--mc-border-light); cursor: pointer; }
  .event-card:last-of-type { border-bottom: 0; }
  .event-card:active { background: var(--mc-bg-muted); }
  .event-card-topline { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
  .event-card-title { display: block; font-size: 15px; line-height: 1.45; overflow-wrap: anywhere; }
  .event-card p { margin: 6px 0 0; color: var(--mc-text-tertiary); font-size: 12px; line-height: 1.55; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
  .event-card .entity-list { flex-wrap: wrap; }
  .cluster-link.mobile { display: inline-flex; }
  .event-card-meta { display: grid; grid-template-columns: minmax(0, 1fr) auto 16px; align-items: center; gap: 8px; margin-top: 12px; color: var(--mc-text-tertiary); font-size: 11px; }
  .event-card-meta span { display: flex; align-items: center; gap: 5px; min-width: 0; }
  .event-card-meta .tier-dot { display: inline-block; }
  .desktop-pagination { display: none; }
  .mobile-pagination { display: flex; max-width: 100%; }
  .pagination-wrap { justify-content: center; padding: 12px 6px; overflow: hidden; }
  .relation-grid { grid-template-columns: 1fr; }
  .cluster-summary-card { align-items: flex-start; }.cluster-summary-meta { display: grid; justify-items: end; }.cluster-history-grid { grid-template-columns: 1fr; }
}
</style>
