<template>
  <div class="ai-news-page">
    <header class="page-header">
      <div>
        <div class="eyebrow">AI NEWS OPS</div>
        <h1>AI 动态工作台</h1>
        <p>从发现候选到证据核验，再到内容生产与交付，所有状态在同一条事件链上可追溯。</p>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" :icon="Refresh" @click="loadEvents">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="showCreate = true">录入候选</el-button>
      </div>
    </header>

    <section class="stage-strip" aria-label="事件流程">
      <div v-for="stage in stages" :key="stage.key" class="stage-item" :class="`stage-${stage.tone}`">
        <span class="stage-dot"></span>
        <span>{{ stage.label }}</span>
        <strong>{{ stage.count }}</strong>
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
          <div class="detail-meta"><span>发现于 {{ formatTime(detail.event.discoveredAt || detail.event.createTime) }}</span><span>置信度 {{ confidenceLabel(detail.event.confidence) }}</span></div>
        </div>

        <div class="detail-section">
          <div class="section-title"><h3>来源证据</h3><span>{{ detail.evidence.length }} 条</span></div>
          <div v-if="detail.evidence.length" class="evidence-list">
            <article v-for="item in detail.evidence" :key="item.id" class="evidence-card">
              <div class="evidence-card-head"><el-tag size="small" :type="tierTagType(item.sourceTier)">{{ tierLabel(item.sourceTier) }}</el-tag><el-tag v-if="item.verified" size="small" type="success" effect="plain">已核验</el-tag><span>{{ confidenceLabel(item.confidence) }}</span></div>
              <el-link :href="item.sourceUrl" target="_blank" type="primary" class="source-link">{{ item.sourceTitle || item.sourceUrl }}</el-link>
              <p class="claim">{{ item.claim }}</p>
              <blockquote v-if="item.quote">“{{ item.quote }}”</blockquote>
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
          <el-button v-if="detail.event.status === 'verified'" type="primary" :icon="Operation" :loading="actionLoading" @click="produceEvent">开始创作</el-button>
          <el-button v-if="canMarkPublished(detail.event)" type="success" plain :icon="Promotion" :loading="actionLoading" @click="markPublished">确认已交付</el-button>
          <el-button v-if="!['archived', 'rejected', 'published'].includes(detail.event.status)" text type="danger" :icon="Delete" :loading="actionLoading" @click="dismissEvent">忽略</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="showCreate" title="录入 AI 动态候选" width="560px" destroy-on-close>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  ArrowRight, ChatDotRound, CircleCheck, Delete, Document, Operation, Picture,
  Plus, Promotion, Refresh, Search, WarningFilled,
} from '@element-plus/icons-vue'
import { aiNewsApi, type AiNewsEvent, type AiNewsEventDetail } from '@/api'

const router = useRouter()
const loading = ref(false)
const detailLoading = ref(false)
const actionLoading = ref(false)
const createLoading = ref(false)
const detailVisible = ref(false)
const showCreate = ref(false)
const detail = ref<AiNewsEventDetail | null>(null)
const events = ref<AiNewsEvent[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const createFormRef = ref<FormInstance>()

const filters = reactive({ keyword: '', category: '', status: '' })
const createForm = reactive({ title: '', category: 'model', summary: '', sourceUrl: '', sourceTier: 'official', claim: '' })
const categories = [
  { value: 'model', label: '模型与研究' }, { value: 'robotics', label: '机器人与具身智能' },
  { value: 'infrastructure', label: '芯片与基础设施' }, { value: 'product', label: '大厂 AI 产品' },
  { value: 'open_source', label: '开源生态' }, { value: 'industry', label: '产业合作' }, { value: 'policy', label: '政策与治理' },
]
const statusOptions = [
  { value: 'candidate', label: '候选' }, { value: 'researching', label: '核验中' }, { value: 'verified', label: '已核验' },
  { value: 'conflicted', label: '有冲突' }, { value: 'in_production', label: '生产中' }, { value: 'published', label: '已交付' },
  { value: 'rejected', label: '已忽略' }, { value: 'archived', label: '已归档' },
]
const stages = computed(() => [
  { key: 'candidate', label: '候选', count: countStatus('candidate'), tone: 'candidate' },
  { key: 'verified', label: '已核验', count: countStatus('verified'), tone: 'verified' },
  { key: 'in_production', label: '生产中', count: countStatus('in_production'), tone: 'production' },
  { key: 'published', label: '已交付', count: countStatus('published'), tone: 'published' },
])
const createRules: FormRules = {
  title: [{ required: true, message: '请输入事件标题', trigger: 'blur' }],
  sourceUrl: [{ required: true, message: '请输入来源 URL', trigger: 'blur' }, { type: 'url', message: '请输入有效 URL', trigger: 'blur' }],
  claim: [{ required: true, message: '请输入事实声明', trigger: 'blur' }],
}

function responseData(res: any) { return res?.data ?? res }
function countStatus(status: string) { return events.value.filter((item) => item.status === status).length }
function categoryLabel(value?: string) { return categories.find((item) => item.value === value)?.label || value || '未分类' }
function statusLabel(value?: string) { return statusOptions.find((item) => item.value === value)?.label || value || '未知' }
function statusType(value?: string): 'success' | 'warning' | 'danger' | 'info' | undefined {
  if (value === 'verified' || value === 'published') return 'success'
  if (value === 'conflicted' || value === 'researching') return 'warning'
  if (value === 'rejected') return 'danger'
  if (value === 'in_production') return undefined
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
function confidenceLabel(value?: number) { return `${Math.round((Number(value) || 0) * 100)}%` }
function formatTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '—' }
function parseJson(value?: string): string[] { try { const parsed = value ? JSON.parse(value) : []; return Array.isArray(parsed) ? parsed.filter((item) => typeof item === 'string') : [] } catch { return [] } }
// Element Plus table slots expose a generic DefaultRow type; the API payload
// is still normalized as AiNewsEvent at the load boundary.
function entitiesOf(row: any) { return parseJson(row.entitiesJson) }
function claimsOf(row: any) { return parseJson(row.claimsJson) }
function conflictsOf(row: any) { return parseJson(row.conflictsJson) }
function canVerify(row: any) { return ['candidate', 'researching', 'conflicted'].includes(row.status) }
function canMarkPublished(row: any) {
  return row.status === 'in_production'
    && (!!row.gzhContentItemId || !!row.xhsContentItemId)
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
function resetAndLoad() { page.value = 1; loadEvents() }
function onSizeChange() { page.value = 1; loadEvents() }
async function openDetail(row: AiNewsEvent) {
  detailVisible.value = true; detailLoading.value = true; detail.value = null
  try { detail.value = responseData(await aiNewsApi.get(row.id)) } catch (error: any) { ElMessage.error(errorMessage(error, '加载事件详情失败')); detailVisible.value = false } finally { detailLoading.value = false }
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

onMounted(loadEvents)
</script>

<style scoped>
.ai-news-page { min-height: 100%; padding: 30px 36px 48px; color: var(--mc-text-primary); }
.page-header { max-width: 1440px; margin: 0 auto 24px; display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; }
.eyebrow { color: var(--mc-accent); font-size: 11px; font-weight: 800; letter-spacing: 0; margin-bottom: 8px; }
.page-header h1 { margin: 0; font-size: 29px; line-height: 1.2; font-weight: 760; }
.page-header p { margin: 8px 0 0; color: var(--mc-text-tertiary); font-size: 13px; line-height: 1.6; }
.header-actions { display: flex; gap: 10px; flex-shrink: 0; }
.stage-strip, .filter-bar, .event-panel { max-width: 1440px; margin-left: auto; margin-right: auto; }
.stage-strip { display: grid; grid-template-columns: repeat(4, 1fr); background: var(--mc-panel-top); border: 1px solid var(--mc-border); border-radius: 8px; overflow: hidden; margin-bottom: 14px; box-shadow: none; }
.stage-item { display: flex; align-items: center; gap: 9px; padding: 14px 18px; border-right: 1px solid var(--mc-border-light); font-size: 13px; color: var(--mc-text-secondary); }
.stage-item:last-child { border-right: 0; }
.stage-item strong { margin-left: auto; color: var(--mc-text-primary); font-size: 18px; }
.stage-dot, .tier-dot { width: 8px; height: 8px; border-radius: 50%; background: #94a3b8; flex: 0 0 auto; }
.stage-candidate .stage-dot { background: #d97706; }.stage-verified .stage-dot { background: #16836d; }.stage-production .stage-dot { background: #2563eb; }.stage-published .stage-dot { background: #0f766e; }
.filter-bar { display: flex; gap: 10px; padding: 12px; background: var(--mc-panel-top); border: 1px solid var(--mc-border); border-radius: 8px; box-shadow: none; margin-bottom: 16px; }
.keyword-input { max-width: 390px; flex: 1; }.filter-bar .el-select { width: 170px; }.filter-bar .el-button { margin-left: auto; }
.event-panel { background: var(--mc-panel-top); border: 1px solid var(--mc-border); border-radius: 8px; box-shadow: none; overflow: hidden; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px 14px; border-bottom: 1px solid var(--mc-border-light); }.panel-heading.compact { padding-bottom: 17px; }
.panel-heading h2 { margin: 0; font-size: 16px; font-weight: 700; }.panel-heading span { display: block; color: var(--mc-text-tertiary); font-size: 12px; margin-top: 4px; }
.event-table { width: 100%; cursor: pointer; --el-table-header-bg-color: var(--mc-bg-muted); --el-table-tr-bg-color: var(--mc-panel-top); --el-table-bg-color: var(--mc-panel-top); --el-table-border-color: var(--mc-border-light); --el-table-row-hover-bg-color: var(--mc-primary-bg); }.event-title-cell { padding: 5px 0; }.event-title { font-weight: 650; line-height: 1.35; }.event-summary { color: var(--mc-text-tertiary); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 500px; margin-top: 4px; }.entity-list { display: flex; gap: 4px; margin-top: 7px; }.entity-list .el-tag { border-radius: 4px; }.evidence-cell { display: grid; grid-template-columns: 9px 1fr; align-items: center; gap: 5px; font-size: 12px; }.evidence-cell small { grid-column: 2; color: var(--mc-text-tertiary); }.tier-dot.official { background: #16836d; }.tier-dot.media { background: #d97706; }.tier-dot.community { background: #2563eb; }.tier-dot.conflict { background: var(--mc-danger); }.time-text { color: var(--mc-text-tertiary); font-size: 12px; }.row-chevron { color: var(--mc-text-tertiary); }
:deep(.event-table .el-table__header th.el-table__cell) { color: var(--mc-text-secondary); font-size: 12px; font-weight: 700; }
:deep(.event-table .el-table__row:hover > td.el-table__cell) { background: var(--mc-primary-bg); }
.pagination-wrap { display: flex; justify-content: flex-end; padding: 14px 18px; border-top: 1px solid var(--mc-border-light); }
.event-cards, .mobile-pagination { display: none; }
.detail-head h2 { margin: 12px 0 7px; font-size: 23px; line-height: 1.35; }.detail-head p { margin: 0; color: var(--mc-text-secondary); line-height: 1.65; }.detail-kicker, .detail-meta { display: flex; align-items: center; gap: 10px; color: var(--mc-text-tertiary); font-size: 12px; }.detail-meta { margin-top: 12px; }.detail-section { margin-top: 26px; }.section-title { display: flex; align-items: center; gap: 10px; margin-bottom: 11px; }.section-title h3 { margin: 0; font-size: 14px; }.section-title span { color: var(--mc-text-tertiary); font-size: 12px; }.evidence-list { display: grid; gap: 9px; }.evidence-card { padding: 13px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg-muted); }.evidence-card-head { display: flex; align-items: center; gap: 7px; margin-bottom: 8px; }.evidence-card-head span { margin-left: auto; color: var(--mc-text-tertiary); font-size: 11px; }.source-link { max-width: 100%; font-size: 13px; }.claim { margin: 8px 0 0; line-height: 1.55; font-size: 13px; }.evidence-card blockquote { margin: 8px 0 0; padding-left: 10px; border-left: 2px solid var(--mc-border); color: var(--mc-text-tertiary); font-size: 12px; line-height: 1.55; }.capture-list { display: grid; gap: 8px; }.capture-row { padding: 11px 12px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg-muted); }.capture-status-line { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; color: var(--mc-text-tertiary); font-size: 11px; }.capture-status-line time { margin-left: auto; }.capture-error { margin: 7px 0 0; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.5; }.claims-section ul { margin: 0; padding-left: 18px; color: var(--mc-text-secondary); font-size: 13px; line-height: 1.7; }.conflict-box { display: flex; gap: 9px; padding: 12px; color: var(--mc-danger); background: var(--mc-danger-bg); border: 1px solid var(--mc-danger-border); border-radius: 8px; font-size: 12px; }.conflict-box p { margin: 4px 0 0; }.relation-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 9px; }.relation-item { display: grid; grid-template-columns: 20px 1fr; text-align: left; gap: 2px 7px; padding: 11px; border: 1px solid var(--mc-border); border-radius: 8px; background: transparent; color: var(--mc-text-secondary); cursor: pointer; }.relation-item:hover:not(:disabled) { border-color: var(--mc-primary); background: var(--mc-bg-muted); }.relation-item:disabled { opacity: .52; cursor: not-allowed; }.relation-item .el-icon { grid-row: 1 / span 2; margin-top: 2px; }.relation-item span { font-size: 11px; }.relation-item strong { color: var(--mc-text-primary); font-size: 12px; }.drawer-actions { display: flex; gap: 9px; flex-wrap: wrap; margin-top: 28px; padding-top: 16px; border-top: 1px solid var(--mc-border-light); }.full-width { width: 100%; }
@media (max-width: 680px) {
  .ai-news-page { padding: 18px 12px 30px; overflow-x: clip; }
  .page-header { display: block; }
  .page-header h1 { font-size: 23px; }
  .page-header p { max-width: 34em; line-height: 1.55; }
  .header-actions { margin-top: 14px; }
  .header-actions .el-button { flex: 1; margin-left: 0; }
  .stage-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .stage-item { padding: 12px 13px; }
  .stage-item:nth-child(2) { border-right: 0; }
  .stage-item:nth-child(-n+2) { border-bottom: 1px solid var(--mc-border-light); }
  .filter-bar { flex-wrap: wrap; padding: 11px; }
  .keyword-input { max-width: none; flex: 1 0 100%; }
  .filter-bar .el-select { width: auto; flex: 1 1 calc(50% - 5px); min-width: 0; }
  .filter-bar .el-button { margin-left: 0; flex: 1 0 100%; }
  .panel-heading { padding: 15px 14px 12px; }
  .event-table { display: none; }
  .event-cards { display: block; min-height: 120px; }
  .event-card { width: 100%; display: block; padding: 15px 14px; text-align: left; color: inherit; background: transparent; border: 0; border-bottom: 1px solid var(--mc-border-light); cursor: pointer; }
  .event-card:last-of-type { border-bottom: 0; }
  .event-card:active { background: var(--mc-bg-muted); }
  .event-card-topline { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
  .event-card-title { display: block; font-size: 15px; line-height: 1.45; overflow-wrap: anywhere; }
  .event-card p { margin: 6px 0 0; color: var(--mc-text-tertiary); font-size: 12px; line-height: 1.55; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
  .event-card .entity-list { flex-wrap: wrap; }
  .event-card-meta { display: grid; grid-template-columns: minmax(0, 1fr) auto 16px; align-items: center; gap: 8px; margin-top: 12px; color: var(--mc-text-tertiary); font-size: 11px; }
  .event-card-meta span { display: flex; align-items: center; gap: 5px; min-width: 0; }
  .event-card-meta .tier-dot { display: inline-block; }
  .desktop-pagination { display: none; }
  .mobile-pagination { display: flex; max-width: 100%; }
  .pagination-wrap { justify-content: center; padding: 12px 6px; overflow: hidden; }
  .relation-grid { grid-template-columns: 1fr; }
}
</style>
