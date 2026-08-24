package vip.mate.channel.weixin.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RFC-024 Change 3：sealed WeixinClientError 分类器的核心行为测试。 */
class WeixinClientErrorTest {

    @Test
    @DisplayName("401 映射为 TokenExpired，toException() 返回 TokenExpiredException")
    void http401MapsToTokenExpired() {
        var err = WeixinClientError.fromStatus(401, "getUpdates", "{\"msg\":\"token invalid\"}");
        assertInstanceOf(WeixinClientError.TokenExpired.class, err);
        assertTrue(err.isTokenExpired());
        var ex = err.toException();
        assertInstanceOf(TokenExpiredException.class, ex);
        assertEquals(401, ((TokenExpiredException) ex).getHttpStatus());
        assertEquals("getUpdates", ((TokenExpiredException) ex).getOperation());
    }

    @Test
    @DisplayName("403 同样映射为 TokenExpired")
    void http403MapsToTokenExpired() {
        var err = WeixinClientError.fromStatus(403, "sendMessage", "");
        assertInstanceOf(WeixinClientError.TokenExpired.class, err);
        assertTrue(err.isTokenExpired());
    }

    @Test
    @DisplayName("400/404/422 → BadRequest")
    void clientErrorsMapToBadRequest() {
        for (int status : new int[]{400, 404, 422}) {
            var err = WeixinClientError.fromStatus(status, "op", "body");
            assertInstanceOf(WeixinClientError.BadRequest.class, err, "status=" + status);
            assertFalse(err.isTokenExpired());
            assertNotNull(err.toException());
        }
    }

    @Test
    @DisplayName("500/502/503 → ServerError")
    void serverErrorsMapToServerError() {
        for (int status : new int[]{500, 502, 503}) {
            var err = WeixinClientError.fromStatus(status, "op", "body");
            assertInstanceOf(WeixinClientError.ServerError.class, err, "status=" + status);
            assertFalse(err.isTokenExpired());
        }
    }

    @Test
    @DisplayName("非 4xx/5xx 状态 → Unknown")
    void unusualStatusMapsToUnknown() {
        var err = WeixinClientError.fromStatus(301, "op", "redirect body");
        assertInstanceOf(WeixinClientError.Unknown.class, err);
    }

    @Test
    @DisplayName("响应体超长时被截断（不撑爆日志）")
    void longBodyTruncatedInExceptionMessage() {
        String bigBody = "x".repeat(2000);
        var err = WeixinClientError.fromStatus(500, "op", bigBody);
        var ex = err.toException();
        // 异常信息里只带前 200 字符
        assertTrue(ex.getMessage().length() < 500,
                "truncated message should be < 500 chars, got " + ex.getMessage().length());
    }
}
