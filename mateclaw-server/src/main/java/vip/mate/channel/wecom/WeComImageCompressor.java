package vip.mate.channel.wecom;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * 企业微信图片压缩工具
 * <p>
 * WeCom 上传限制 2MB，安全阈值 1.9MB。
 * 策略：PNG/RGBA → JPEG RGB → 逐级降低质量 → 逐级缩放尺寸。
 */
@Slf4j
class WeComImageCompressor {

    private static final long MAX_UPLOAD_SIZE = 1_900_000; // 1.9MB
    private static final float[] QUALITY_STEPS = {0.85f, 0.70f, 0.50f, 0.30f};
    private static final double[] SCALE_STEPS = {0.75, 0.50, 0.25};

    private WeComImageCompressor() {}

    /**
     * 压缩图片以满足 WeCom 上传大小限制
     *
     * @param imageBytes 原始图片字节
     * @param fileName   原始文件名（用于判断格式）
     * @return 压缩后的 JPEG 字节（如已小于阈值则返回原始数据）
     */
    static byte[] compressIfNeeded(byte[] imageBytes, String fileName) {
        if (imageBytes == null || imageBytes.length == 0) {
            return imageBytes;
        }
        if (imageBytes.length <= MAX_UPLOAD_SIZE) {
            return imageBytes;
        }

        log.info("[wecom] compress_image: original size {}KB > limit {}KB",
                imageBytes.length / 1024, MAX_UPLOAD_SIZE / 1024);

        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) {
                log.warn("[wecom] compress_image: failed to read image, returning original");
                return imageBytes;
            }

            // Convert to RGB (drop alpha for JPEG)
            BufferedImage rgbImg = toRgb(img);

            // Try progressive quality reduction
            for (float quality : QUALITY_STEPS) {
                byte[] compressed = writeJpeg(rgbImg, quality);
                if (compressed.length <= MAX_UPLOAD_SIZE) {
                    log.info("[wecom] compress_image: compressed to {}KB (quality={})",
                            compressed.length / 1024, quality);
                    return compressed;
                }
            }

            // Try resize + quality
            int w = rgbImg.getWidth();
            int h = rgbImg.getHeight();
            byte[] smallest = null;
            for (double scale : SCALE_STEPS) {
                BufferedImage resized = resize(rgbImg, (int) (w * scale), (int) (h * scale));
                byte[] compressed = writeJpeg(resized, 0.50f);
                smallest = compressed;
                if (compressed.length <= MAX_UPLOAD_SIZE) {
                    log.info("[wecom] compress_image: resized to {}x{}, {}KB",
                            (int) (w * scale), (int) (h * scale), compressed.length / 1024);
                    return compressed;
                }
            }

            // Return smallest we got
            log.warn("[wecom] compress_image: could not compress below limit, returning smallest ({}KB)",
                    smallest != null ? smallest.length / 1024 : 0);
            return smallest != null ? smallest : imageBytes;

        } catch (Exception e) {
            log.error("[wecom] compress_image failed, returning original: {}", e.getMessage());
            return imageBytes;
        }
    }

    private static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static byte[] writeJpeg(BufferedImage img, float quality) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG ImageWriter found");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            }
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private static BufferedImage resize(BufferedImage img, int newWidth, int newHeight) {
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newWidth, newHeight);
        g.drawImage(img, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return resized;
    }
}
