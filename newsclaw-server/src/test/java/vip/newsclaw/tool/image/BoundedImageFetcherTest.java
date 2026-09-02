package vip.newsclaw.tool.image;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedImageFetcherTest {

    @Test
    void acceptsRasterMagicAndRejectsHtmlOrOversizedPayloads() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0};
        assertDoesNotThrow(() -> BoundedImageFetcher.validate(png, "image/png"));
        assertThrows(IOException.class, () -> BoundedImageFetcher.validate("<html>".getBytes(), "text/html"));
        assertThrows(IOException.class, () -> BoundedImageFetcher.requireTotal(BoundedImageFetcher.MAX_TOTAL_BYTES + 1L));
    }
}
