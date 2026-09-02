package vip.newsclaw.tool.image;

import vip.newsclaw.tool.browser.UrlSafetyChecker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** One bounded, SSRF-checked image reader for publishing/package tools. */
public final class BoundedImageFetcher {

    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 40 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private BoundedImageFetcher() {}

    public record Image(byte[] bytes, String mimeType) {}

    public static Image http(String rawUrl) throws IOException {
        String current = rawUrl == null ? "" : rawUrl.trim();
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            UrlSafetyChecker.check(current);
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp;q=0.9,*/*;q=0.1");
            try {
                int status = connection.getResponseCode();
                if (status / 100 == 3) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank() || redirect == MAX_REDIRECTS) {
                        throw new IOException("image redirect chain is invalid or too long");
                    }
                    URI next = URI.create(current).resolve(location.trim());
                    if (next.getScheme() == null || next.getHost() == null
                            || !("http".equalsIgnoreCase(next.getScheme()) || "https".equalsIgnoreCase(next.getScheme()))) {
                        throw new IOException("image redirect target is not http(s)");
                    }
                    current = next.toString();
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("image download returned HTTP " + status);
                }
                String mime = normalizeMime(connection.getContentType());
                long declared = connection.getContentLengthLong();
                if (declared > MAX_IMAGE_BYTES) {
                    throw new IOException("image exceeds " + MAX_IMAGE_BYTES + " bytes");
                }
                byte[] bytes = readBounded(connection.getInputStream(), MAX_IMAGE_BYTES);
                validate(bytes, mime);
                return new Image(bytes, mime);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("image redirect chain is too long");
    }

    public static Image file(Path path, String mimeType) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IOException("image file does not exist");
        if (Files.size(path) > MAX_IMAGE_BYTES) throw new IOException("image exceeds " + MAX_IMAGE_BYTES + " bytes");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = readBounded(input, MAX_IMAGE_BYTES);
        }
        String mime = normalizeMime(mimeType);
        validate(bytes, mime);
        return new Image(bytes, mime);
    }

    public static void validate(byte[] bytes, String mimeType) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("image is empty");
        if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("image exceeds " + MAX_IMAGE_BYTES + " bytes");
        String mime = normalizeMime(mimeType);
        if (mime != null && (!mime.startsWith("image/") || "image/svg+xml".equals(mime))) {
            throw new IOException("only raster images are accepted");
        }
        if (!looksLikeRaster(bytes)) throw new IOException("image bytes do not match a supported raster format");
    }

    public static void requireTotal(long total) throws IOException {
        if (total > MAX_TOTAL_BYTES) throw new IOException("image bundle exceeds " + MAX_TOTAL_BYTES + " bytes");
    }

    private static byte[] readBounded(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
            total += n;
            if (total > max) throw new IOException("image exceeds " + max + " bytes");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String normalizeMime(String mime) {
        if (mime == null || mime.isBlank()) return null;
        int semi = mime.indexOf(';');
        return (semi < 0 ? mime : mime.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeRaster(byte[] b) {
        return (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47)
                || (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff)
                || (b.length >= 6 && ((b[0] == 'G' && b[1] == 'I' && b[2] == 'F')
                && (b[3] == '8' && (b[4] == '7' || b[4] == '9') && b[5] == 'a')))
                || (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P');
    }
}
