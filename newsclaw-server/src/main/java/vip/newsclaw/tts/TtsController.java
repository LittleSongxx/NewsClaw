package vip.newsclaw.tts;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import vip.newsclaw.workspace.conversation.ConversationService;

import java.util.List;
import java.util.Map;

/**
 * TTS 语音合成 REST 端点
 *
 * @author NewsClaw Team
 */
@RestController
@RequestMapping("/api/v1/tts")
@RequiredArgsConstructor
public class TtsController {

    private final TtsService ttsService;
    private ConversationService conversationService;

    /** Optional for source-compatible lightweight controller tests. */
    @Autowired(required = false)
    void setConversationService(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 合成语音 — 前端"朗读"按钮调用
     */
    @PostMapping("/synthesize")
    public ResponseEntity<Map<String, Object>> synthesize(@RequestBody SynthesizeRequest req,
                                                          Authentication authentication) {
        if (req == null || req.getConversationId() == null || req.getConversationId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "conversationId is required"));
        }
        if (authentication == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "error", "authentication required"));
        }
        if (conversationService != null
                && !conversationService.isConversationOwner(req.getConversationId(), authentication.getName())) {
            return ResponseEntity.status(403)
                    .body(Map.of("success", false, "error", "conversation access denied"));
        }
        Map<String, Object> result = ttsService.synthesize(
                req.getConversationId(),
                req.getText(),
                req.getVoice(),
                req.getSpeed(),
                req.getFormat()
        );
        return ResponseEntity.ok(result);
    }

    /**
     * 列出所有可用语音
     */
    @GetMapping("/voices")
    public ResponseEntity<List<Map<String, Object>>> listVoices() {
        return ResponseEntity.ok(ttsService.listVoices());
    }

    @Data
    public static class SynthesizeRequest {
        private String conversationId;
        private String text;
        private String voice;
        private Double speed;
        private String format;
    }
}
