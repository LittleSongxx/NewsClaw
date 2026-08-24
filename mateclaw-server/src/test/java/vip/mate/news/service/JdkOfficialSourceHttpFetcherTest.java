package vip.mate.news.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JdkOfficialSourceHttpFetcherTest {

    @Test
    @DisplayName("只读抓取器在建连前拒绝本地地址，避免 SSRF")
    void blocksLoopbackBeforeNetworkCall() {
        JdkOfficialSourceHttpFetcher fetcher = new JdkOfficialSourceHttpFetcher();

        assertThrows(SecurityException.class,
                () -> fetcher.fetch("http://127.0.0.1:18080/private", 1024, 1, 0));
    }
}
