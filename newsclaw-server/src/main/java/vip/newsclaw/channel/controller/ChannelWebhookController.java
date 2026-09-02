package vip.newsclaw.channel.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import vip.newsclaw.channel.ChannelAdapter;
import vip.newsclaw.channel.ChannelManager;
import vip.newsclaw.channel.dingtalk.DingTalkAppRegistrationService;
import vip.newsclaw.channel.dingtalk.DingTalkChannelAdapter;
import vip.newsclaw.channel.discord.DiscordChannelAdapter;
import vip.newsclaw.channel.feishu.FeishuAppRegistrationService;
import vip.newsclaw.channel.feishu.FeishuChannelAdapter;
import vip.newsclaw.channel.qrcode.util.QrCodeImageEncoder;
import vip.newsclaw.channel.telegram.TelegramChannelAdapter;
import vip.newsclaw.channel.weixin.ILinkClient;
import vip.newsclaw.channel.weixin.WeixinChannelAdapter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

/**
 * 渠道 Webhook 回调接口
 * <p>
 * 接收来自各 IM 平台的消息推送回调。
 * 各平台（钉钉、飞书、Telegram 等）将此 URL 配置为消息回调地址。
 * <p>
 * URL 格式：/api/v1/channels/webhook/{channelType}
 * 此接口不需要 JWT 认证（由各平台的签名/Token 机制保障安全）。
 *
 * @author NewsClaw Team
 */
@Tag(name = "渠道Webhook")
@Slf4j
@RestController
@RequestMapping("/api/v1/channels/webhook")
@RequiredArgsConstructor
public class ChannelWebhookController {

    private final ChannelManager channelManager;
    private final FeishuAppRegistrationService feishuAppRegistrationService;
    private final DingTalkAppRegistrationService dingTalkAppRegistrationService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "钉钉消息回调")
    @PostMapping({"/dingtalk", "/dingtalk/{channelId}"})
    public ResponseEntity<Map<String, Object>> dingtalkWebhook(
            @PathVariable(required = false) Long channelId,
            @RequestHeader(value = "timestamp", required = false) String timestamp,
            @RequestHeader(value = "sign", required = false) String signature,
            @RequestParam(value = "timestamp", required = false) String timestampParam,
            @RequestParam(value = "sign", required = false) String signatureParam,
            @RequestBody Map<String, Object> payload) {
        log.debug("[webhook] DingTalk callback received");
        String effectiveTimestamp = timestamp != null ? timestamp : timestampParam;
        String effectiveSignature = signature != null ? signature : signatureParam;
        DingTalkChannelAdapter dingtalk = candidates("dingtalk", channelId).stream()
                .filter(DingTalkChannelAdapter.class::isInstance)
                .map(DingTalkChannelAdapter.class::cast)
                .filter(adapter -> adapter.acceptsWebhook(effectiveTimestamp, effectiveSignature))
                .findFirst().orElse(null);
        if (dingtalk != null) {
            dingtalk.handleWebhook(payload);
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
        log.warn("[webhook] DingTalk callback rejected: no matching signed channel");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "invalid_signature"));
    }

    // ==================== 钉钉一键应用注册（OAuth Device Flow） ====================

    @Operation(summary = "启动钉钉扫码注册应用流程")
    @PostMapping("/dingtalk/register/begin")
    public ResponseEntity<Map<String, Object>> dingtalkRegisterBegin(Authentication auth) {
        requireAdmin(auth);
        try {
            DingTalkAppRegistrationService.RegistrationSession session = dingTalkAppRegistrationService.begin();
            return ResponseEntity.ok(Map.of("session_id", session.sessionId));
        } catch (Exception e) {
            log.error("[dingtalk-register] begin failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start registration: " + e.getMessage()));
        }
    }

    @Operation(summary = "查询钉钉扫码注册状态")
    @GetMapping("/dingtalk/register/status")
    public ResponseEntity<Map<String, Object>> dingtalkRegisterStatus(
            @RequestParam("session") String sessionId, Authentication auth) {
        requireAdmin(auth);
        DingTalkAppRegistrationService.RegistrationSession session = dingTalkAppRegistrationService.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("status", "expired", "error", "session not found or expired"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", session.status.name().toLowerCase());
        if (session.qrcodeUrl != null) {
            body.put("qrcode_url", session.qrcodeUrl);
            // Same as the feishu register flow: SDK gives us a verification URL string,
            // browsers can't render that as an image, so encode into a PNG data URI here
            // and cache it on the session so ZXing only runs once per registration attempt.
            if (session.qrcodeImgDataUri == null) {
                try {
                    String base64 = generateQrCodeBase64(session.qrcodeUrl);
                    session.qrcodeImgDataUri = "data:image/png;base64," + base64;
                } catch (Exception e) {
                    log.warn("[dingtalk-register] QR encode failed: {}", e.getMessage());
                }
            }
            if (session.qrcodeImgDataUri != null) {
                body.put("qrcode_img", session.qrcodeImgDataUri);
            }
        }
        if (session.status == DingTalkAppRegistrationService.Status.CONFIRMED) {
            body.put("client_id", session.clientId);
            body.put("client_secret", session.clientSecret);
        }
        if (session.errorMessage != null) {
            body.put("error", session.errorMessage);
        }
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "飞书消息回调")
    @PostMapping({"/feishu", "/feishu/{channelId}"})
    public ResponseEntity<Map<String, Object>> feishuWebhook(
            @PathVariable(required = false) Long channelId,
            @RequestBody Map<String, Object> payload) {
        log.debug("[webhook] Feishu callback received");
        for (ChannelAdapter candidate : candidates("feishu", channelId)) {
            if (!(candidate instanceof FeishuChannelAdapter feishu)) continue;
            Map<String, Object> verified = feishu.verifyAndDecodeWebhook(payload);
            if (verified == null) continue;
            Map<String, Object> result = feishu.handleWebhook(verified);
            return ResponseEntity.ok(result);
        }
        log.warn("[webhook] Feishu callback rejected: no matching verified app");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", 401));
    }

    // ==================== 飞书一键应用注册（oapi-sdk 2.6+） ====================

    @Operation(summary = "启动飞书扫码注册应用流程")
    @PostMapping("/feishu/register/begin")
    public ResponseEntity<Map<String, Object>> feishuRegisterBegin(
            @RequestParam(value = "domain", defaultValue = "feishu") String domain,
            Authentication auth) {
        requireAdmin(auth);
        try {
            String sessionId = feishuAppRegistrationService.begin(domain);
            return ResponseEntity.ok(Map.of("session_id", sessionId));
        } catch (Exception e) {
            log.error("[feishu-register] begin failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start registration: " + e.getMessage()));
        }
    }

    @Operation(summary = "查询飞书扫码注册状态")
    @GetMapping("/feishu/register/status")
    public ResponseEntity<Map<String, Object>> feishuRegisterStatus(
            @RequestParam("session") String sessionId, Authentication auth) {
        requireAdmin(auth);
        FeishuAppRegistrationService.RegistrationSession session = feishuAppRegistrationService.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("status", "expired", "error", "session not found or expired"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", session.status.name().toLowerCase());
        if (session.qrcodeUrl != null) {
            body.put("qrcode_url", session.qrcodeUrl);
            body.put("qrcode_expire_seconds", session.qrcodeExpireSeconds);
            // SDK gives us a verification URL string (verification_uri_complete);
            // browsers can't render that as an image, so encode it into a PNG QR
            // here just like the WeChat flow does. Cache the encoded image on the
            // session so we only run ZXing once per registration attempt.
            if (session.qrcodeImgDataUri == null) {
                try {
                    String base64 = generateQrCodeBase64(session.qrcodeUrl);
                    session.qrcodeImgDataUri = "data:image/png;base64," + base64;
                } catch (Exception e) {
                    log.warn("[feishu-register] QR encode failed: {}", e.getMessage());
                }
            }
            if (session.qrcodeImgDataUri != null) {
                body.put("qrcode_img", session.qrcodeImgDataUri);
            }
        }
        if (session.status == FeishuAppRegistrationService.Status.CONFIRMED) {
            body.put("client_id", session.clientId);
            body.put("client_secret", session.clientSecret);
            if (session.userOpenId != null) body.put("user_open_id", session.userOpenId);
            if (session.userTenantBrand != null) body.put("user_tenant_brand", session.userTenantBrand);
        }
        if (session.errorMessage != null) {
            body.put("error", session.errorMessage);
        }
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Telegram 消息回调")
    @PostMapping({"/telegram", "/telegram/{channelId}"})
    public ResponseEntity<String> telegramWebhook(
            @PathVariable(required = false) Long channelId,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
            @RequestBody Map<String, Object> payload) {
        log.debug("[webhook] Telegram callback received");
        TelegramChannelAdapter telegram = candidates("telegram", channelId).stream()
                .filter(TelegramChannelAdapter.class::isInstance)
                .map(TelegramChannelAdapter.class::cast)
                .filter(adapter -> adapter.acceptsWebhookSecret(secret))
                .findFirst().orElse(null);
        if (telegram != null) {
            telegram.handleWebhook(payload);
        } else {
            log.warn("[webhook] Telegram callback rejected: no matching secret/channel");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid webhook secret");
        }
        return ResponseEntity.ok("ok");
    }

    @Operation(summary = "Discord 消息回调（已废弃：Discord 已切换为 Gateway WebSocket 模式）")
    @PostMapping("/discord")
    public ResponseEntity<Map<String, Object>> discordWebhook(@RequestBody Map<String, Object> payload) {
        // Discord Interaction PING 仍需响应（防止 Discord 删除 Interaction URL）
        Integer type = (Integer) payload.get("type");
        if (type != null && type == 1) {
            return ResponseEntity.ok(Map.of("type", 1));
        }

        // Discord 已切换为 Gateway WebSocket，webhook 回调不再用于接收消息
        log.warn("[webhook] Discord webhook called, but messages are now received via Gateway WebSocket");
        Optional<ChannelAdapter> adapter = channelManager.getAdapterByType("discord");
        if (adapter.isPresent() && adapter.get() instanceof DiscordChannelAdapter discord) {
            discord.handleWebhook(payload);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @Operation(summary = "企业微信消息回调（智能机器人模式不使用，保留兼容）")
    @PostMapping("/wecom")
    public ResponseEntity<String> wecomWebhook(@RequestBody Map<String, Object> payload) {
        // 智能机器人模式通过 WebSocket 长连接接收消息，不再使用 HTTP 回调
        log.debug("[webhook] WeCom callback received (not used in bot mode, messages are received via WebSocket)");
        return ResponseEntity.ok("success");
    }

    @Operation(summary = "Slack Events API 回调")
    @PostMapping({"/slack", "/slack/{channelId}"})
    public ResponseEntity<Map<String, Object>> slackWebhook(
            @PathVariable(required = false) Long channelId,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        log.debug("[webhook] Slack callback received");
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception invalid) {
            return ResponseEntity.badRequest().body(Map.of("status", "invalid_json"));
        }
        vip.newsclaw.channel.slack.SlackChannelAdapter slack = candidates("slack", channelId).stream()
                .filter(vip.newsclaw.channel.slack.SlackChannelAdapter.class::isInstance)
                .map(vip.newsclaw.channel.slack.SlackChannelAdapter.class::cast)
                .filter(adapter -> adapter.acceptsWebhook(rawBody, timestamp, signature))
                .findFirst().orElse(null);
        if (slack == null) {
            log.warn("[webhook] Slack callback rejected: no matching signed channel");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "invalid_signature"));
        }
        // URL Verification challenge
        if ("url_verification".equals(payload.get("type"))) {
            return ResponseEntity.ok(Map.of("challenge", payload.getOrDefault("challenge", "")));
        }
        return ResponseEntity.ok(slack.handleWebhook(payload));
    }

    // ==================== 微信 iLink Bot ====================

    /** 微信扫码深链接模板 */
    private static final String WEIXIN_SCAN_URL_TEMPLATE =
            "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=%s&bot_type=3";

    /**
     * 创建临时 ILinkClient 用于 QR 码操作（不依赖渠道是否已启动）
     */
    private ILinkClient createWeixinClient() {
        return new ILinkClient("", ILinkClient.DEFAULT_BASE_URL,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /** Delegated to the shared encoder so future tweaks live in one place. */
    private String generateQrCodeBase64(String content) throws Exception {
        return QrCodeImageEncoder.toBase64(content);
    }

    @Operation(summary = "获取微信登录二维码")
    @GetMapping("/weixin/qrcode")
    public ResponseEntity<Map<String, Object>> weixinQrcode(Authentication auth) {
        requireAdmin(auth);
        try {
            ILinkClient client = createWeixinClient();
            Map<String, Object> apiResult = client.getBotQrcode();

            String qrcode = String.valueOf(apiResult.getOrDefault("qrcode", ""));
            if (qrcode.isBlank()) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "iLink API returned empty qrcode"));
            }

            // 从 qrcode 构建微信扫码深链接，再生成 QR 码图片
            String scanUrl;
            Object urlObj = apiResult.get("url");
            if (urlObj != null && urlObj.toString().startsWith("http")) {
                scanUrl = urlObj.toString();
            } else {
                String encoded = URLEncoder.encode(qrcode, StandardCharsets.UTF_8);
                scanUrl = String.format(WEIXIN_SCAN_URL_TEMPLATE, encoded);
            }

            String qrCodeImgBase64 = generateQrCodeBase64(scanUrl);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("qrcode", qrcode);
            result.put("qrcode_img", qrCodeImgBase64);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[webhook] WeChat QR code fetch failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get QR code: " + e.getMessage()));
        }
    }

    @Operation(summary = "查询微信二维码扫码状态")
    @GetMapping("/weixin/qrcode/status")
    public ResponseEntity<Map<String, Object>> weixinQrcodeStatus(
            @RequestParam String qrcode, Authentication auth) {
        requireAdmin(auth);
        try {
            ILinkClient client = createWeixinClient();
            Map<String, Object> apiResult = client.getQrcodeStatus(qrcode);

            // 只返回前端需要的字段
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", apiResult.getOrDefault("status", "waiting"));
            result.put("bot_token", apiResult.getOrDefault("bot_token", ""));
            result.put("base_url", apiResult.getOrDefault("baseurl", ""));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[webhook] WeChat QR code status check failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to check QR code status: " + e.getMessage()));
        }
    }

    @Operation(summary = "获取渠道运行状态")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(Authentication auth) {
        requireAdmin(auth);
        return ResponseEntity.ok(channelManager.getStatus());
    }

    private List<ChannelAdapter> candidates(String type, Long channelId) {
        if (channelId == null) return channelManager.getAdaptersByType(type);
        ChannelAdapter adapter = channelManager.getAdapter(channelId).orElse(null);
        return adapter != null && type.equals(adapter.getChannelType()) ? List.of(adapter) : List.of();
    }

    /** The whole controller path is permitAll for platform callbacks, so the
     * human registration helpers enforce their own JWT admin gate. */
    private void requireAdmin(Authentication auth) {
        boolean admin = auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            throw new ResponseStatusException(auth == null
                    ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN);
        }
    }
}
