package vip.newsclaw.tool.model3d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.newsclaw.channel.AsyncTaskMediaDispatcher;
import vip.newsclaw.system.model.SystemSettingsDTO;
import vip.newsclaw.system.service.SystemSettingService;
import vip.newsclaw.task.AsyncTaskService;
import vip.newsclaw.task.AsyncTaskService.TaskPollResult;
import vip.newsclaw.task.model.AsyncTaskEntity;
import vip.newsclaw.task.model.AsyncTaskInfo;
import vip.newsclaw.workspace.conversation.ConversationService;
import vip.newsclaw.workspace.conversation.model.MessageContentPart;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D-model generation service. Mirrors {@link vip.newsclaw.tool.video.VideoGenerationService}.
 * <p>
 * Provider workflow: submit → AsyncTask polling → on terminal-success download
 * the .glb to local storage → write {@code model3d} MessageContentPart →
 * broadcast {@code async_task_completed} via the generic data map.
 * <p>
 * Provider-side {@link TaskPollResult} carries the model URL via
 * {@code resultJson} (a JSON string with {@code modelUrl} / optional
 * {@code format}) — TaskPollResult's structured fields are tied to image/video
 * URLs and we don't want to widen the record's positional constructor for a
 * single new media kind.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Model3dGenerationService {

    private final SystemSettingService systemSettingService;
    private final Model3dProviderRegistry providerRegistry;
    private final AsyncTaskService asyncTaskService;
    private final ConversationService conversationService;
    private final Model3dFileDownloader fileDownloader;
    private final ObjectMapper objectMapper;
    /**
     * Forward async-task completion to the conversation's bound IM channel
     * adapter so users on WeCom / DingTalk / Feishu / etc. receive the
     * generated 3D model as a native attachment. SSE remains the Web path.
     */
    private final AsyncTaskMediaDispatcher asyncTaskMediaDispatcher;

    private static final String TASK_TYPE = "model3d_generation";

    public Model3dGenerationResult submitGeneration(Model3dGenerationRequest request,
                                                      String conversationId,
                                                      String createdBy) {
        SystemSettingsDTO config = systemSettingService.getAllSettings();

        if (!Boolean.TRUE.equals(config.getModel3dEnabled())) {
            return Model3dGenerationResult.failure("3D 模型生成功能未启用，请在系统设置中开启");
        }

        if (request.getMode() == null) {
            request.setMode(inferMode(request));
        }

        Model3dGenerationProvider primary = providerRegistry.resolve(config, request.getMode());
        if (primary == null) {
            return Model3dGenerationResult.failure(
                    "没有可用的 3D 生成 Provider，请在系统设置中配置（当前支持腾讯混元 3D）");
        }

        return submitWithFallback(request, config, primary, conversationId, createdBy);
    }

    public AsyncTaskInfo checkTaskStatus(String taskId) {
        return asyncTaskService.getTaskInfo(taskId);
    }

    // ==================== internal ====================

    private Model3dGenerationResult submitWithFallback(Model3dGenerationRequest request,
                                                         SystemSettingsDTO config,
                                                         Model3dGenerationProvider primary,
                                                         String conversationId,
                                                         String createdBy) {
        normalizeForProvider(request, primary);
        Model3dSubmitResult submitResult = primary.submit(request, config);
        if (submitResult.isAccepted()) {
            return createAsyncTask(submitResult, request, conversationId, createdBy);
        }

        List<String> errors = new ArrayList<>();
        errors.add(primary.id() + ": " + submitResult.getErrorMessage());

        if (Boolean.TRUE.equals(config.getModel3dFallbackEnabled())) {
            for (Model3dGenerationProvider fb : providerRegistry.fallbackCandidates(
                    config, request.getMode(), primary.id())) {
                log.info("[Model3dGen] Trying fallback provider: {}", fb.id());
                normalizeForProvider(request, fb);
                submitResult = fb.submit(request, config);
                if (submitResult.isAccepted()) {
                    return createAsyncTask(submitResult, request, conversationId, createdBy);
                }
                errors.add(fb.id() + ": " + submitResult.getErrorMessage());
                log.warn("[Model3dGen] Fallback {} failed: {}", fb.id(), submitResult.getErrorMessage());
            }
        }

        return Model3dGenerationResult.failure(
                "所有 Provider 均提交失败\n" + String.join("\n", errors));
    }

    private Model3dGenerationResult createAsyncTask(Model3dSubmitResult submitResult,
                                                      Model3dGenerationRequest request,
                                                      String conversationId,
                                                      String createdBy) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            AsyncTaskEntity task = asyncTaskService.createTask(
                    TASK_TYPE, conversationId, null,
                    submitResult.getProviderName(),
                    submitResult.getProviderTaskId(),
                    requestJson, createdBy);

            Model3dGenerationProvider provider = providerRegistry.getById(submitResult.getProviderName());
            if (provider == null) {
                return Model3dGenerationResult.failure("Provider 不存在: " + submitResult.getProviderName());
            }

            asyncTaskService.startPolling(
                    task.getTaskId(),
                    providerTaskId -> provider.checkStatus(providerTaskId, systemSettingService.getAllSettings()),
                    this::handleCompletion);

            return Model3dGenerationResult.success(task.getTaskId(), submitResult.getProviderName());
        } catch (Exception e) {
            log.error("[Model3dGen] Failed to create async task: {}", e.getMessage(), e);
            return Model3dGenerationResult.failure("创建任务失败: " + e.getMessage());
        }
    }

    private void handleCompletion(AsyncTaskEntity task, TaskPollResult result) {
        // A conversation may be deleted while the provider is still running;
        // never recreate its attachment directory or message row afterwards.
        if (asyncTaskService.isConversationCanceled(task.getConversationId())) {
            log.info("[Model3dGen] Task {} (success={}) aborted: conversation {} was deleted",
                    task.getTaskId(), result.succeeded(), task.getConversationId());
            return;
        }
        if (!result.succeeded()) {
            asyncTaskService.broadcastTaskEventWithData(task, "async_task_completed",
                    false, Map.of(), result.errorMessage());
            log.warn("[Model3dGen] Task {} failed: {}", task.getTaskId(), result.errorMessage());
            return;
        }

        try {
            // The provider stuffs {modelUrl, format?} into resultJson.
            String resultJson = result.resultJson();
            String modelUrl = null;
            String format = null;
            if (resultJson != null && !resultJson.isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(resultJson);
                    modelUrl = node.path("modelUrl").asText(null);
                    format = node.path("format").asText(null);
                } catch (Exception e) {
                    log.warn("[Model3dGen] Failed to parse resultJson for task {}: {}",
                            task.getTaskId(), e.getMessage());
                }
            }
            if (modelUrl == null || modelUrl.isBlank()) {
                log.warn("[Model3dGen] Task {} succeeded but no model URL in resultJson", task.getTaskId());
                asyncTaskService.broadcastTaskEventWithData(task, "async_task_completed",
                        false, Map.of(), "3D 生成成功但未返回模型 URL");
                return;
            }

            Path localPath = fileDownloader.download(
                    modelUrl, task.getConversationId(), task.getTaskId(), format);
            // Cancellation can arrive while the provider download is in flight.
            // Do not leave an orphaned attachment behind, and do not continue
            // into message/broadcast side effects after the conversation was
            // deleted.
            if (asyncTaskService.isConversationCanceled(task.getConversationId())) {
                deleteCanceledDownload(task, localPath);
                return;
            }
            String servingUrl = fileDownloader.toServingUrl(task.getConversationId(), localPath);

            String fileName = localPath.getFileName().toString();
            MessageContentPart modelPart = MessageContentPart.model3d(null, fileName);
            modelPart.setFileUrl(servingUrl);
            modelPart.setStoredName(fileName);
            // model/gltf-binary for .glb is the iana-registered MIME; downstream
            // <model-viewer> only cares about the URL, not the MIME header.
            if (fileName.endsWith(".glb")) {
                modelPart.setContentType("model/gltf-binary");
            } else if (fileName.endsWith(".obj")) {
                modelPart.setContentType("model/obj");
            } else if (fileName.endsWith(".fbx")) {
                modelPart.setContentType("model/fbx");
            } else if (fileName.endsWith(".usdz")) {
                modelPart.setContentType("model/vnd.usdz+zip");
            }
            // Set absolute disk path so IM adapters can read bytes locally
            // instead of round-tripping through /api/v1/chat/files (auth).
            modelPart.setPath(localPath.toAbsolutePath().toString());
            try {
                modelPart.setFileSize(java.nio.file.Files.size(localPath));
            } catch (Exception ignored) { /* best-effort */ }

            List<MessageContentPart> parts = List.of(modelPart);
            conversationService.saveMessage(
                    task.getConversationId(), "assistant",
                    "3D 模型已生成完毕",
                    parts, "completed");

            if (asyncTaskService.isConversationCanceled(task.getConversationId())) {
                // The save may have raced with deletion; suppress all further
                // fan-out and remove the file when it is still present.
                deleteCanceledDownload(task, localPath);
                return;
            }

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("modelUrl", servingUrl);
            if (format != null) extra.put("format", format);
            asyncTaskService.broadcastTaskEventWithData(task, "async_task_completed",
                    true, extra, null);

            // Forward to the conversation's bound IM channel adapter so
            // WeCom / DingTalk / Feishu etc. users receive the model as a
            // native attachment (the SSE broadcast above only reaches Web).
            // Most IM channels will fall back to a markdown link via
            // sendFallbackText if their adapter doesn't natively support
            // model/* media — that's fine, the dispatcher logs and continues.
            asyncTaskMediaDispatcher.forwardToImIfBound(task.getConversationId(), parts);

            log.info("[Model3dGen] Task {} completed, model saved: {}", task.getTaskId(), servingUrl);
        } catch (Exception e) {
            log.error("[Model3dGen] Completion handling failed for task {}: {}",
                    task.getTaskId(), e.getMessage(), e);
            asyncTaskService.broadcastTaskEventWithData(task, "async_task_completed",
                    false, Map.of(), "3D 模型下载或保存失败: " + e.getMessage());
        }
    }

    private void deleteCanceledDownload(AsyncTaskEntity task, Path localPath) {
        try {
            java.nio.file.Files.deleteIfExists(localPath);
        } catch (Exception cleanupError) {
            log.warn("[Model3dGen] Could not remove canceled download for task {}: {}",
                    task.getTaskId(), cleanupError.getMessage());
        }
        log.info("[Model3dGen] Task {} download discarded after conversation cancellation",
                task.getTaskId());
    }

    private Model3dCapability inferMode(Model3dGenerationRequest request) {
        if (request.getImageUrls() != null && request.getImageUrls().size() > 1) {
            return Model3dCapability.MULTI_VIEW_TO_3D;
        }
        if (request.getImageUrl() != null && !request.getImageUrl().isBlank()) {
            return Model3dCapability.IMAGE_TO_3D;
        }
        if (request.getImageUrls() != null && !request.getImageUrls().isEmpty()) {
            return Model3dCapability.IMAGE_TO_3D;
        }
        return Model3dCapability.TEXT_TO_3D;
    }

    private void normalizeForProvider(Model3dGenerationRequest request, Model3dGenerationProvider provider) {
        Model3dProviderCapabilities caps = provider.detailedCapabilities();
        if (caps == null) return;
        request.setOutputFormat(caps.normalizeFormat(request.getOutputFormat()));
    }
}
