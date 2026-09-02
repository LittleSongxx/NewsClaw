package vip.newsclaw.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.draft.WxMpAddDraft;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import me.chanjar.weixin.mp.bean.material.WxMediaImgUploadResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.newsclaw.system.service.SystemSettingService;
import vip.newsclaw.tool.document.GeneratedFileCache;
import vip.newsclaw.tool.image.BoundedImageFetcher;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Built-in tool: publish a generated 图文 article to a WeChat Official Account.
 *
 * <p>The only enabled endpoint is the <b>draft box</b> (草稿箱): the tool uploads
 * the cover image as a permanent material and creates a draft article via the
 * Official Account draft API. The account owner then reviews and taps
 * "publish" in the WeChat backend. Direct mass-send is intentionally disabled
 * here because a model-supplied boolean is not proof of a human approval and the
 * action is externally visible and irreversible.
 *
 * <p>Credentials are read from system settings ({@code weixinoa.app_id} /
 * {@code weixinoa.app_secret}); nothing runs until they are configured.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GzhPublishTool {

    private static final String SETTING_APP_ID = "weixinoa.app_id";
    private static final String SETTING_APP_SECRET = "weixinoa.app_secret";
    private static final int MAX_HTML_CHARS = 200_000;

    private final SystemSettingService systemSettingService;
    private final WxMpServiceProvider wxMpServiceProvider;
    private final GeneratedFileCache generatedFileCache;

    @Value("${newsclaw.tools.external-publish.enabled:false}")
    private boolean externalPublishEnabled;

    @Tool(name = "gzh_publish", description = """
        Publish a generated image-text article to a WeChat Official Account (微信公众号).

        Actions:
          - draft (default): upload the cover image and create a draft in the
            Official Account 草稿箱. The user then reviews and publishes it in
            the WeChat backend.
          - publish: disabled. Direct free-publish is deliberately unavailable
            until a server-issued, one-time human approval is implemented; the
            `confirmPublish` argument is retained only for compatibility and is
            never trusted as an approval proof.

        `content` must be WeChat-editor-compatible HTML with INLINE styles only
        (公众号 ignores <style> blocks). `coverImageUrl` is required for a draft
        (WeChat requires a cover / thumb). Requires weixinoa.app_id and
        weixinoa.app_secret to be configured in system settings.
        """)
    public String gzh_publish(
            @ToolParam(description = "Action: draft (default) or publish", required = false)
            String action,
            @ToolParam(description = "Article title (required for draft)", required = false)
            String title,
            @ToolParam(description = "Article body as inline-styled HTML (required for draft)", required = false)
            String content,
            @ToolParam(description = "Cover image URL — uploaded as the article thumb (required for draft)", required = false)
            String coverImageUrl,
            @ToolParam(description = "Author / source name", required = false)
            String author,
            @ToolParam(description = "Short summary shown in the article list (<=120 chars); auto-derived if omitted", required = false)
            String digest,
            @ToolParam(description = "Draft media_id to free-publish (required for publish action)", required = false)
            String draftMediaId,
            @ToolParam(description = "Must be true to actually free-publish; forces explicit user confirmation", required = false)
            Boolean confirmPublish) {

        String act = (action == null || action.isBlank()) ? "draft" : action.trim().toLowerCase();
        if ("publish".equals(act)) {
            return "Error: direct WeChat free-publish is disabled. Create a draft and publish it manually in the WeChat backend; confirmPublish is not an approval token.";
        }
        if (!externalPublishEnabled) {
            return "Error: external publishing tools are disabled in this deployment; use the reviewed package flow instead.";
        }
        String appId = systemSettingService.getString(SETTING_APP_ID, "");
        String appSecret = systemSettingService.getString(SETTING_APP_SECRET, "");
        if (appId.isBlank() || appSecret.isBlank()) {
            return "Error: WeChat Official Account is not configured. Set '" + SETTING_APP_ID
                    + "' and '" + SETTING_APP_SECRET + "' in system settings first.";
        }

        WxMpService wxMpService = wxMpServiceProvider.getService(appId, appSecret);

        return switch (act) {
            case "draft" -> createDraft(wxMpService, title, content, coverImageUrl, author, digest);
            default -> "Error: unknown action '" + act + "'. Use 'draft'.";
        };
    }

    private String createDraft(WxMpService wxMpService, String title, String content,
                               String coverImageUrl, String author, String digest) {
        if (title == null || title.isBlank()) {
            return "Error: title is required for a draft.";
        }
        if (content == null || content.isBlank()) {
            return "Error: content (inline-styled HTML) is required for a draft.";
        }
        if (content.length() > MAX_HTML_CHARS) {
            return "Error: content exceeds the 200000-character draft limit.";
        }
        if (coverImageUrl == null || coverImageUrl.isBlank()) {
            return "Error: coverImageUrl is required — WeChat needs a cover/thumb for the article.";
        }

        // Hard compliance gate (fail fast, before any upload): refuse to draft
        // account-fatal copy — 广告法 极限词 / 微信诱导 / 承诺收益. Lower-risk hits
        // (医疗功效) are surfaced as a warning on success instead.
        ComplianceScanner.Result scan = ComplianceScanner.scan(
                title + "\n" + content.replaceAll("<[^>]+>", " "));
        if (scan.hasHighRisk()) {
            return "⛔ 合规拦截：命中高危违规词，已阻止进入草稿箱，请替换后再发。\n"
                    + ComplianceScanner.report(scan);
        }

        // 1. Download the cover and upload it as a permanent image material -> thumb media_id.
        String thumbMediaId;
        File tmpCover = null;
        try {
            BoundedImageFetcher.Image cover = resolveImage(coverImageUrl);
            tmpCover = Files.createTempFile("gzh_cover_", ".img").toFile();
            Files.write(tmpCover.toPath(), cover.bytes());
            WxMpMaterial material = new WxMpMaterial();
            material.setName(tmpCover.getName());
            material.setFile(tmpCover);
            WxMpMaterialUploadResult uploaded = withRetry(() -> wxMpService.getMaterialService()
                    .materialFileUpload(WxConsts.MediaFileType.IMAGE, material));
            thumbMediaId = uploaded.getMediaId();
            if (thumbMediaId == null || thumbMediaId.isBlank()) {
                return "Error: cover upload returned no media_id.";
            }
        } catch (WxErrorException e) {
            log.warn("[GzhPublish] cover upload failed: {}", e.getMessage());
            return "Error: 封面上传失败 — " + translateWxError(e);
        } catch (Exception e) {
            log.warn("[GzhPublish] cover download/upload failed: {}", e.getMessage());
            return "Error: cover download/upload failed — " + e.getMessage();
        } finally {
            if (tmpCover != null) {
                //noinspection ResultOfMethodCallIgnored
                tmpCover.delete();
            }
        }

        // 2. Inline body images into WeChat: article HTML with external <img src>
        //    (our generated-file URLs, localhost, any non-mp host) renders broken in
        //    the published article — WeChat only displays images it hosts. Upload each
        //    and rewrite src to the returned mp.weixin.qq.com URL. Failures don't block
        //    the draft; they're reported so the user can fix those images by hand.
        ImageInlineResult inlined = inlineContentImages(wxMpService, content);
        String bodyHtml = inlined.html();

        // 3. Build the draft article and submit it.
        try {
            WxMpDraftArticles article = new WxMpDraftArticles();
            article.setTitle(trimTo(title, 64));
            article.setContent(bodyHtml);
            article.setThumbMediaId(thumbMediaId);
            if (author != null && !author.isBlank()) {
                article.setAuthor(trimTo(author, 8));
            }
            article.setDigest(digest != null && !digest.isBlank()
                    ? trimTo(digest, 120)
                    : deriveDigest(content));

            String draftMediaId = withRetry(() -> wxMpService.getDraftService()
                    .addDraft(new WxMpAddDraft(List.of(article))));
            log.info("[GzhPublish] draft created, media_id={}, title='{}', imagesInlined={}, imagesFailed={}",
                    draftMediaId, title, inlined.uploaded(), inlined.failed().size());
            StringBuilder ok = new StringBuilder();
            ok.append("✅ 已存入公众号草稿箱。\n");
            ok.append("draft media_id: ").append(draftMediaId).append('\n');
            if (inlined.uploaded() > 0) {
                ok.append("正文图已上传微信并改写链接：").append(inlined.uploaded()).append(" 张。\n");
            }
            if (!inlined.failed().isEmpty()) {
                ok.append("⚠️ 有 ").append(inlined.failed().size())
                  .append(" 张正文图未能上传（发布后会裂图，请在后台手动替换）：")
                  .append(String.join("；", inlined.failed())).append('\n');
            }
            if (!scan.clean()) {
                ok.append("⚠️ 合规提示（非高危，建议核对）：").append(ComplianceScanner.report(scan)).append('\n');
            }
            ok.append("请到公众号后台「草稿箱」核对排版后点击「发表」；NewsClaw 当前不提供机器人直接群发入口。");
            return ok.toString();
        } catch (WxErrorException e) {
            log.warn("[GzhPublish] addDraft failed: {}", e.getMessage());
            return "Error: 创建草稿失败 — " + translateWxError(e);
        }
    }

    @FunctionalInterface
    private interface WxCall<T> {
        T get() throws WxErrorException;
    }

    /**
     * Retry a WeChat call on transient error codes (system busy / rate limit) with
     * a short backoff, up to 2 extra attempts. Non-transient errors (bad token,
     * IP whitelist, unauthorized) throw immediately — retrying them is pointless.
     */
    private static <T> T withRetry(WxCall<T> call) throws WxErrorException {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (WxErrorException e) {
                int code = e.getError() != null ? e.getError().getErrorCode() : 0;
                boolean transient_ = (code == -1 || code == 45009);
                if (transient_ && attempt < 2) {
                    attempt++;
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }

    /** Translate a WeChat error into an actionable Chinese hint (falls back to the raw message). */
    static String translateWxError(WxErrorException e) {
        int code = e.getError() != null ? e.getError().getErrorCode() : 0;
        String hint = switch (code) {
            case 40164 -> "服务器公网 IP 不在公众号后台白名单。到「设置与开发 → 安全中心 / IP 白名单」把本机公网 IP 加进去后重试。";
            case 48001 -> "接口未授权：该能力通常仅认证服务号可用。";
            case 40001, 42001 -> "access_token 无效或已过期：请核对 AppID/AppSecret，或稍后重试。";
            case 45009 -> "接口调用频率超限：请稍后再试。";
            case -1 -> "微信系统繁忙：请稍后再试。";
            default -> "";
        };
        return hint.isEmpty()
                ? (e.getMessage() == null ? "微信接口错误" : e.getMessage())
                : hint + "（errcode=" + code + "）";
    }

    /** Result of rewriting article body images to WeChat-hosted URLs. */
    record ImageInlineResult(String html, int uploaded, List<String> failed) {}

    /**
     * Upload every non-WeChat body image to the Official Account and rewrite its
     * {@code src} to the returned {@code mp.weixin.qq.com} URL, so images actually
     * render in the published article. Images already on {@code mp.weixin.qq.com}
     * (or {@code data:} URIs) are left as-is. A single image failing to upload is
     * recorded and skipped — it never blocks the whole draft.
     */
    ImageInlineResult inlineContentImages(WxMpService wxMpService, String html) {
        if (html == null || html.isBlank()) {
            return new ImageInlineResult(html, 0, List.of());
        }
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        List<String> failed = new ArrayList<>();
        int uploaded = 0;
        long totalBytes = 0;
        for (Element img : doc.select("img[src]")) {
            String src = img.attr("src").trim();
            if (src.isEmpty() || src.contains("mp.weixin.qq.com") || src.startsWith("data:")) {
                continue;
            }
            File tmp = null;
            try {
                byte[] bytes = resolveImageBytes(src);
                if (bytes == null || bytes.length == 0) {
                    failed.add(src);
                    continue;
                }
                totalBytes += bytes.length;
                if (totalBytes > BoundedImageFetcher.MAX_TOTAL_BYTES) {
                    failed.add(src + " (图片总量超过上限)");
                    continue;
                }
                tmp = Files.createTempFile("gzh_img_", "." + extOf(src)).toFile();
                Files.write(tmp.toPath(), bytes);
                WxMediaImgUploadResult result = wxMpService.getMaterialService().mediaImgUpload(tmp);
                if (result != null && result.getUrl() != null && !result.getUrl().isBlank()) {
                    img.attr("src", result.getUrl());
                    uploaded++;
                } else {
                    failed.add(src);
                }
            } catch (Exception e) {
                log.warn("[GzhPublish] content image upload failed for {}: {}", src, e.getMessage());
                failed.add(src);
            } finally {
                if (tmp != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }
        return new ImageInlineResult(doc.body().html(), uploaded, failed);
    }

    /** Resolve an article-body image ref to bytes: our generated files, or http(s). */
    private byte[] resolveImageBytes(String src) {
        try {
            Matcher m = GeneratedFileCache.GENERATED_URL_PATTERN.matcher(src);
            if (m.find()) {
                Optional<GeneratedFileCache.Entry> e = generatedFileCache.get(m.group(1));
                if (e.isEmpty() || e.get().bytes() == null
                        || e.get().bytes().length > BoundedImageFetcher.MAX_IMAGE_BYTES) return null;
                return e.get().bytes();
            }
            if (src.startsWith("http://") || src.startsWith("https://")) {
                return BoundedImageFetcher.http(src).bytes();
            }
        } catch (Exception e) {
            log.debug("[GzhPublish] could not resolve body image {}: {}", src, e.toString());
        }
        return null;
    }

    private BoundedImageFetcher.Image resolveImage(String ref) throws Exception {
        Matcher m = GeneratedFileCache.GENERATED_URL_PATTERN.matcher(ref == null ? "" : ref.trim());
        if (m.find()) {
            Optional<GeneratedFileCache.Entry> entry = generatedFileCache.get(m.group(1));
            if (entry.isEmpty()) throw new IllegalStateException("生成文件已过期或不存在");
            BoundedImageFetcher.validate(entry.get().bytes(), entry.get().mimeType());
            return new BoundedImageFetcher.Image(entry.get().bytes(), entry.get().mimeType());
        }
        if (ref != null && (ref.startsWith("http://") || ref.startsWith("https://"))) {
            return BoundedImageFetcher.http(ref);
        }
        throw new IllegalArgumentException("coverImageUrl must be a generated-file or safe http(s) image URL");
    }

    private static String extOf(String url) {
        String clean = url.split("[?#]")[0];
        int dot = clean.lastIndexOf('.');
        if (dot >= 0 && dot < clean.length() - 1) {
            String ext = clean.substring(dot + 1).toLowerCase();
            if (ext.matches("(png|jpg|jpeg|gif|webp)")) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        return "jpg";
    }

    /** Strip tags and clamp to a length for the article digest. */
    private static String deriveDigest(String htmlContent) {
        String plain = htmlContent.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return trimTo(plain, 120);
    }

    private static String trimTo(String v, int max) {
        if (v == null) {
            return "";
        }
        String t = v.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
