package vip.newsclaw.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.agent.binding.model.AgentWikiKbBinding;
import vip.newsclaw.agent.binding.repository.AgentWikiKbBindingMapper;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.wiki.job.model.WikiProcessingJobEntity;
import vip.newsclaw.wiki.model.WikiChunkEntity;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.model.WikiPageCitationEntity;
import vip.newsclaw.wiki.model.WikiPageEntity;
import vip.newsclaw.wiki.model.WikiRawMaterialEntity;
import vip.newsclaw.wiki.model.WikiRelationEntity;
import vip.newsclaw.wiki.model.WikiHotCacheEntity;
import vip.newsclaw.wiki.model.WikiTransformationEntity;
import vip.newsclaw.wiki.model.WikiTransformationRunEntity;
import vip.newsclaw.wiki.model.WikiPageDependencyEntity;
import vip.newsclaw.wiki.model.WikiPageTypeProfileEntity;
import vip.newsclaw.wiki.model.WikiAgentPageTypePermissionEntity;
import vip.newsclaw.wiki.model.WikiPipelineDefinitionEntity;
import vip.newsclaw.wiki.model.WikiPipelineRunEntity;
import vip.newsclaw.wiki.model.WikiPipelineStepRunEntity;
import vip.newsclaw.wiki.model.WikiEntityEntity;
import vip.newsclaw.wiki.model.WikiEntityMentionEntity;
import vip.newsclaw.wiki.model.WikiEntityRelationEntity;
import vip.newsclaw.wiki.repository.WikiChunkMapper;
import vip.newsclaw.wiki.repository.WikiKnowledgeBaseMapper;
import vip.newsclaw.wiki.repository.WikiPageCitationMapper;
import vip.newsclaw.wiki.repository.WikiPageMapper;
import vip.newsclaw.wiki.repository.WikiProcessingJobMapper;
import vip.newsclaw.wiki.repository.WikiRawMaterialMapper;
import vip.newsclaw.wiki.repository.WikiRelationMapper;
import vip.newsclaw.wiki.repository.WikiHotCacheMapper;
import vip.newsclaw.wiki.repository.WikiTransformationMapper;
import vip.newsclaw.wiki.repository.WikiTransformationRunMapper;
import vip.newsclaw.wiki.repository.WikiPageDependencyMapper;
import vip.newsclaw.wiki.repository.WikiPageTypeProfileMapper;
import vip.newsclaw.wiki.repository.WikiAgentPageTypePermissionMapper;
import vip.newsclaw.wiki.repository.WikiPipelineDefinitionMapper;
import vip.newsclaw.wiki.repository.WikiPipelineRunMapper;
import vip.newsclaw.wiki.repository.WikiPipelineStepRunMapper;
import vip.newsclaw.wiki.repository.WikiEntityMapper;
import vip.newsclaw.wiki.repository.WikiEntityMentionMapper;
import vip.newsclaw.wiki.repository.WikiEntityRelationMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wiki 知识库服务
 *
 * @author NewsClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiKnowledgeBaseService {

    private final WikiKnowledgeBaseMapper kbMapper;
    private final WikiRawMaterialMapper rawMapper;
    private final WikiPageMapper pageMapper;
    private final WikiChunkMapper chunkMapper;
    private final WikiPageCitationMapper citationMapper;
    private final WikiProcessingJobMapper processingJobMapper;
    private final AgentMapper agentMapper;

    /**
     * RFC-051 PR-2: optional system-page scaffold (overview / log). Marked
     * required=false + Lazy so the KB service has no construction dependency
     * on a service that needs WikiPageService — handy for the older tests that
     * still wire this class manually.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private WikiScaffoldService scaffoldService;

    /**
     * Per-agent KB access scope. Optional ({@code required=false}) so the
     * older tests that hand-wire this service via {@code @RequiredArgsConstructor}
     * still compile and run — a {@code null} mapper means "no scoping known",
     * which falls through to the legacy workspace-wide visibility.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentWikiKbBindingMapper kbBindingMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiRelationMapper relationMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiHotCacheMapper hotCacheMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiTransformationMapper transformationMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiTransformationRunMapper transformationRunMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiPageDependencyMapper dependencyMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiPageTypeProfileMapper profileMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiAgentPageTypePermissionMapper permissionMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiPipelineDefinitionMapper pipelineDefinitionMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiPipelineRunMapper pipelineRunMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiPipelineStepRunMapper pipelineStepRunMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiEntityMapper entityMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiEntityMentionMapper entityMentionMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WikiEntityRelationMapper entityRelationMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private vip.newsclaw.wiki.WikiProperties wikiProperties;

    /**
     * Summary returned from cascade delete — used by callers (e.g. the
     * controller) to record an audit event with affected-row counts.
     */
    public record CascadeDeleteResult(
            String kbName,
            int rawMaterialCount,
            int pageCount,
            int chunkCount,
            int citationCount,
            int processingJobCount) {
    }

    private static final String DEFAULT_CONFIG = """
            # Wiki Processing Rules

            ## Quality First
            - Create high-quality pages — prefer fewer complete pages over many shallow ones
            - Each page focuses on one concept, entity, or process
            - A page must have at least 3 sentences of substantive content
            - Target 3-5 pages per source material (not 10-15)
            - If a concept already exists in the wiki, update it instead of duplicating

            ## Format
            - Use clear Markdown headers (## and ###)
            - Include a one-paragraph summary at the top of each page
            - Use [[Page Title]] syntax for bidirectional links between pages

            ## Updates
            - Merge new information into existing pages, do not replace
            - Preserve manually edited content (last_updated_by = 'manual')
            - Mark contradictions clearly with a "Note:" annotation

            ## Language
            - Write wiki pages in the same language as the source material
            - Keep technical terms consistent across pages
            """;

    public List<WikiKnowledgeBaseEntity> listAll() {
        return kbMapper.selectList(
                new LambdaQueryWrapper<WikiKnowledgeBaseEntity>()
                        .orderByDesc(WikiKnowledgeBaseEntity::getUpdateTime));
    }

    /**
     * 按工作区列出知识库
     */
    public List<WikiKnowledgeBaseEntity> listByWorkspace(Long workspaceId) {
        return kbMapper.selectList(
                new LambdaQueryWrapper<WikiKnowledgeBaseEntity>()
                        .eq(WikiKnowledgeBaseEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(WikiKnowledgeBaseEntity::getUpdateTime));
    }

    /**
     * 获取 Agent 可访问的知识库。
     * <p>
     * Knowledge bases are workspace-shared, so the baseline visible set is
     * every KB in the agent's workspace. When the agent has been pinned to a
     * subset via {@code mate_agent_wiki_kb}, the set is narrowed to those KBs
     * (intersected with the workspace, so a stale binding to a moved/deleted
     * KB just drops out). An agent with no scope rows stays workspace-wide,
     * preserving the pre-scoping behavior for every existing agent.
     * <p>
     * An agent flagged {@code wiki_disabled=true} sees no KBs at all — the
     * opt-out toggle wins over both workspace visibility and any leftover
     * binding rows (the UI clears the rows when the flag is set, so the
     * zero-row state must not fall through to "unrestricted").
     * <p>
     * This is the single choke point for KB access: {@code wiki_list_kbs},
     * {@link #findVisibleById}, {@link #findAllByName},
     * {@link #resolvePrimaryKb}, the system-prompt wiki context, and the
     * per-turn relevant-page injection all read through here, so narrowing
     * it scopes every wiki surface at once.
     */
    public List<WikiKnowledgeBaseEntity> listByAgentId(Long agentId) {
        AgentEntity agent = getAgentOrNull(agentId);
        if (agent != null && Boolean.TRUE.equals(agent.getWikiDisabled())) {
            return List.of();
        }
        List<WikiKnowledgeBaseEntity> workspaceKbs = (agent == null || agent.getWorkspaceId() == null)
                ? listAll()
                : listByWorkspace(agent.getWorkspaceId());
        Set<Long> scope = scopedKbIds(agentId);
        if (scope == null) {
            return workspaceKbs; // unrestricted
        }
        return workspaceKbs.stream()
                .filter(kb -> scope.contains(kb.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Enabled KB ids this agent is pinned to, or {@code null} when the agent
     * is unrestricted (no scope rows, or the binding mapper isn't wired — see
     * {@link #kbBindingMapper}). Returning {@code null} rather than an empty
     * set is deliberate: an empty set would mean "no KB visible", but a fresh
     * agent must default to its whole workspace.
     */
    private Set<Long> scopedKbIds(Long agentId) {
        if (agentId == null || kbBindingMapper == null) {
            return null;
        }
        List<AgentWikiKbBinding> rows = kbBindingMapper.selectList(
                new LambdaQueryWrapper<AgentWikiKbBinding>()
                        .eq(AgentWikiKbBinding::getAgentId, agentId)
                        .eq(AgentWikiKbBinding::getEnabled, true));
        if (rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .map(AgentWikiKbBinding::getKbId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Resolve the single knowledge base an agent's wiki tools should operate on.
     * <p>
     * Prefers mate_agent.primary_kb_id when it points to a KB in the same
     * workspace. For legacy rows that predate primary_kb_id, falls back to the
     * old mate_wiki_knowledge_base.agent_id marker only when no primary is set.
     * Otherwise the most recently updated workspace KB wins.
     */
    public WikiKnowledgeBaseEntity resolvePrimaryKb(Long agentId) {
        AgentEntity agent = getAgentOrNull(agentId);
        List<WikiKnowledgeBaseEntity> kbs = listByAgentId(agentId);
        if (kbs.isEmpty()) {
            return null;
        }
        Long primaryKbId = agent != null ? agent.getPrimaryKbId() : null;
        if (primaryKbId != null) {
            for (WikiKnowledgeBaseEntity kb : kbs) {
                if (primaryKbId.equals(kb.getId()) && sameWorkspace(agent, kb)) {
                    return kb;
                }
            }
            return kbs.get(0);
        }
        if (agentId != null) {
            for (WikiKnowledgeBaseEntity kb : kbs) {
                if (agentId.equals(kb.getAgentId())) {
                    return kb;
                }
            }
        }
        return kbs.get(0);
    }

    private AgentEntity getAgentOrNull(Long agentId) {
        if (agentId == null || agentMapper == null) {
            return null;
        }
        return agentMapper.selectById(agentId);
    }

    private boolean sameWorkspace(AgentEntity agent, WikiKnowledgeBaseEntity kb) {
        if (agent == null || kb == null || agent.getWorkspaceId() == null) {
            return true;
        }
        return kb.getWorkspaceId() == null || agent.getWorkspaceId().equals(kb.getWorkspaceId());
    }

    /**
     * Resolve a specific knowledge base by name, restricted to the agent's
     * workspace-visible KB set. Used by wiki tools
     * that accept an optional {@code kbName} parameter so the LLM can target
     * a non-primary KB when the agent reaches more than one.
     * <p>
     * Match is exact and case-sensitive — the LLM is expected to copy the
     * name verbatim from {@code wiki_list_kbs} output. Returns {@code null}
     * when zero OR more than one KB matches the name; callers wanting to
     * distinguish the two cases (so the LLM can be told to disambiguate by
     * id) should call {@link #findAllByName} instead. The single-match
     * convenience contract here keeps the legacy call sites simple.
     */
    public WikiKnowledgeBaseEntity findByName(Long agentId, String kbName) {
        List<WikiKnowledgeBaseEntity> matches = findAllByName(agentId, kbName);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * All KBs visible to {@code agentId} whose name matches {@code kbName}
     * exactly. Returns an empty list when the name is blank or no KB matches;
     * returns >1 entries when the workspace has duplicate KB names (no DB
     * unique constraint protects against this), in which case the caller
     * MUST disambiguate (typically by surfacing a kbId-based picker to the
     * LLM) rather than silently picking the first one.
     */
    public List<WikiKnowledgeBaseEntity> findAllByName(Long agentId, String kbName) {
        if (kbName == null || kbName.isBlank()) {
            return List.of();
        }
        List<WikiKnowledgeBaseEntity> out = new java.util.ArrayList<>();
        for (WikiKnowledgeBaseEntity kb : listByAgentId(agentId)) {
            if (kbName.equals(kb.getName())) {
                out.add(kb);
            }
        }
        return out;
    }

    /**
     * Resolve a KB by id, but ONLY when it's in the agent's visibility set —
     * a deliberate fail-closed gate so an LLM cannot pivot to an arbitrary KB
     * by guessing or scraping an id from someone else's workspace.
     */
    public WikiKnowledgeBaseEntity findVisibleById(Long agentId, Long kbId) {
        if (kbId == null) return null;
        for (WikiKnowledgeBaseEntity kb : listByAgentId(agentId)) {
            if (kbId.equals(kb.getId())) {
                return kb;
            }
        }
        return null;
    }

    public WikiKnowledgeBaseEntity getById(Long id) {
        return kbMapper.selectById(id);
    }

    @Transactional
    public WikiKnowledgeBaseEntity create(String name, String description, Long agentId) {
        return create(name, description, agentId, 1L);
    }

    @Transactional
    public WikiKnowledgeBaseEntity create(String name, String description, Long agentId, Long workspaceId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setName(name);
        entity.setDescription(description);
        entity.setAgentId(agentId);
        entity.setWorkspaceId(workspaceId);
        entity.setConfigContent(DEFAULT_CONFIG);
        entity.setStatus("active");
        entity.setPageCount(0);
        entity.setRawCount(0);
        kbMapper.insert(entity);
        log.info("[Wiki] Knowledge base created: id={}, name={}, workspaceId={}", entity.getId(), name, workspaceId);
        // RFC-051 PR-2: ensure overview / log system pages exist for every new KB.
        if (scaffoldService != null) {
            scaffoldService.ensureScaffold(entity.getId());
        }
        return entity;
    }

    @Transactional
    public WikiKnowledgeBaseEntity update(Long id, String name, String description) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        if (name != null) entity.setName(name);
        if (description != null) entity.setDescription(description);
        kbMapper.updateById(entity);
        return entity;
    }

    /**
     * 更新 KB 绑定的 embedding 模型 ID。
     * <p>
     * 切换模型后，旧的向量维度/语义空间与新模型不一致，下次搜索/处理时会被
     * WikiEmbeddingService 自动检测为"model 不匹配"触发重嵌。
     * 这里不主动清空 embedding（让 embed_model 字段的差异自己触发重建）。
     *
     * @param embeddingModelId null 表示解绑（走系统默认）
     */
    @Transactional
    public void updateEmbeddingModelId(Long id, Long embeddingModelId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        Long previous = entity.getEmbeddingModelId();
        entity.setEmbeddingModelId(embeddingModelId);
        kbMapper.updateById(entity);
        if (!java.util.Objects.equals(previous, embeddingModelId)) {
            chunkMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiChunkEntity>()
                            .eq(WikiChunkEntity::getKbId, id)
                            .set(WikiChunkEntity::getEmbedding, null)
                            .set(WikiChunkEntity::getEmbeddingModel, null)
                            .set(WikiChunkEntity::getEmbeddingTextVersion, null));
            pageMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                            .eq(WikiPageEntity::getKbId, id)
                            .set(WikiPageEntity::getEmbedding, null)
                            .set(WikiPageEntity::getEmbeddingModel, null)
                            .set(WikiPageEntity::getEmbeddingTextVersion, null));
        }
    }

    @Transactional
    public void updateConfig(Long id, String configContent) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        entity.setConfigContent(configContent);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateCounts(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        // counts will be updated by callers via specific methods
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateStatus(Long kbId, String status) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setStatus(status);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void incrementRawCount(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setRawCount(entity.getRawCount() + 1);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void setPageCount(Long kbId, int count) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setPageCount(count);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void updateSourceDirectory(Long id, String path) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        entity.setSourceDirectory(path);
        kbMapper.updateById(entity);
    }

    /** Toggle per-KB auto-sync (the periodic source-watcher scan). */
    @Transactional
    public void updateWatcherEnabled(Long id, boolean enabled) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }
        entity.setWatcherEnabled(enabled ? 1 : 0);
        kbMapper.updateById(entity);
    }

    @Transactional
    public void decrementRawCount(Long kbId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity == null) return;
        entity.setRawCount(Math.max(0, entity.getRawCount() - 1));
        kbMapper.updateById(entity);
    }

    /**
     * 更新知识库的 workspace 归属
     */
    public void updateWorkspaceId(Long kbId, Long workspaceId) {
        WikiKnowledgeBaseEntity entity = kbMapper.selectById(kbId);
        if (entity != null) {
            entity.setWorkspaceId(workspaceId);
            kbMapper.updateById(entity);
        }
    }

    /**
     * Cascade-delete a knowledge base and all data that belongs to it.
     * <p>
     * Single transaction: removes page citations (looked up via page IDs since
     * the citation table has no {@code kb_id} column), then chunks, pages, raw
     * materials, and processing jobs by {@code kb_id}, and finally the KB row
     * itself. Returns a summary so callers can record audit metadata.
     */
    @Transactional
    public CascadeDeleteResult delete(Long id) {
        WikiKnowledgeBaseEntity kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + id);
        }

        List<String> managedUploadPaths = rawMapper.selectList(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .select(WikiRawMaterialEntity::getSourcePath)
                        .eq(WikiRawMaterialEntity::getKbId, id))
                .stream().map(WikiRawMaterialEntity::getSourcePath)
                .filter(java.util.Objects::nonNull).filter(p -> !p.isBlank()).toList();

        List<Long> pageIds = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .select(WikiPageEntity::getId)
                        .eq(WikiPageEntity::getKbId, id))
                .stream()
                .map(WikiPageEntity::getId)
                .toList();

        int citationCount = pageIds.isEmpty() ? 0 : citationMapper.delete(
                new LambdaQueryWrapper<WikiPageCitationEntity>()
                        .in(WikiPageCitationEntity::getPageId, pageIds));

        if (relationMapper != null) relationMapper.delete(
                new LambdaQueryWrapper<WikiRelationEntity>().eq(WikiRelationEntity::getKbId, id));
        if (hotCacheMapper != null) hotCacheMapper.delete(
                new LambdaQueryWrapper<WikiHotCacheEntity>().eq(WikiHotCacheEntity::getKbId, id));
        if (transformationRunMapper != null) transformationRunMapper.delete(
                new LambdaQueryWrapper<WikiTransformationRunEntity>().eq(WikiTransformationRunEntity::getKbId, id));
        if (transformationMapper != null) transformationMapper.delete(
                new LambdaQueryWrapper<WikiTransformationEntity>().eq(WikiTransformationEntity::getKbId, id));
        if (dependencyMapper != null) dependencyMapper.delete(
                new LambdaQueryWrapper<WikiPageDependencyEntity>().eq(WikiPageDependencyEntity::getKbId, id));
        if (profileMapper != null) profileMapper.delete(
                new LambdaQueryWrapper<WikiPageTypeProfileEntity>().eq(WikiPageTypeProfileEntity::getKbId, id));
        if (permissionMapper != null) permissionMapper.delete(
                new LambdaQueryWrapper<WikiAgentPageTypePermissionEntity>()
                        .eq(WikiAgentPageTypePermissionEntity::getKbId, id));

        if (pipelineRunMapper != null && pipelineStepRunMapper != null) {
            List<Long> runIds = pipelineRunMapper.selectList(
                    new LambdaQueryWrapper<WikiPipelineRunEntity>()
                            .select(WikiPipelineRunEntity::getId)
                            .eq(WikiPipelineRunEntity::getKbId, id))
                    .stream().map(WikiPipelineRunEntity::getId).toList();
            if (!runIds.isEmpty()) pipelineStepRunMapper.delete(
                    new LambdaQueryWrapper<WikiPipelineStepRunEntity>()
                            .in(WikiPipelineStepRunEntity::getRunId, runIds));
            pipelineRunMapper.delete(
                    new LambdaQueryWrapper<WikiPipelineRunEntity>().eq(WikiPipelineRunEntity::getKbId, id));
        }
        if (pipelineDefinitionMapper != null) pipelineDefinitionMapper.delete(
                new LambdaQueryWrapper<WikiPipelineDefinitionEntity>()
                        .eq(WikiPipelineDefinitionEntity::getKbId, id));

        if (entityMentionMapper != null) entityMentionMapper.delete(
                new LambdaQueryWrapper<WikiEntityMentionEntity>().eq(WikiEntityMentionEntity::getKbId, id));
        if (entityRelationMapper != null) entityRelationMapper.delete(
                new LambdaQueryWrapper<WikiEntityRelationEntity>().eq(WikiEntityRelationEntity::getKbId, id));
        if (entityMapper != null) entityMapper.delete(
                new LambdaQueryWrapper<WikiEntityEntity>().eq(WikiEntityEntity::getKbId, id));
        if (kbBindingMapper != null) kbBindingMapper.delete(
                new LambdaQueryWrapper<AgentWikiKbBinding>().eq(AgentWikiKbBinding::getKbId, id));
        if (agentMapper != null) agentMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentEntity>()
                        .eq(AgentEntity::getPrimaryKbId, id)
                        .set(AgentEntity::getPrimaryKbId, null));

        int pageCount = pageMapper.delete(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, id));

        int chunkCount = chunkMapper.delete(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, id));

        int rawCount = rawMapper.delete(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, id));

        int jobCount = processingJobMapper.delete(
                new LambdaQueryWrapper<WikiProcessingJobEntity>()
                        .eq(WikiProcessingJobEntity::getKbId, id));

        kbMapper.deleteById(id);
        deleteManagedUploadsAfterCommit(managedUploadPaths);

        log.info("[Wiki] Knowledge base cascade-deleted: id={}, name={}, raw={}, page={}, chunk={}, citation={}, job={}",
                id, kb.getName(), rawCount, pageCount, chunkCount, citationCount, jobCount);

        return new CascadeDeleteResult(kb.getName(), rawCount, pageCount, chunkCount, citationCount, jobCount);
    }

    private void deleteManagedUploadsAfterCommit(List<String> paths) {
        if (paths == null || paths.isEmpty() || wikiProperties == null) return;
        Runnable cleanup = () -> {
            java.nio.file.Path root = java.nio.file.Paths.get(wikiProperties.getUploadDir())
                    .toAbsolutePath().normalize();
            for (String sourcePath : paths) {
                try {
                    java.nio.file.Path target = java.nio.file.Paths.get(sourcePath).toAbsolutePath().normalize();
                    if (target.startsWith(root)) java.nio.file.Files.deleteIfExists(target);
                } catch (Exception e) {
                    log.warn("[Wiki] Failed to delete KB upload {}: {}", sourcePath, e.getMessage());
                }
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { cleanup.run(); }
                    });
        } else {
            cleanup.run();
        }
    }
}
