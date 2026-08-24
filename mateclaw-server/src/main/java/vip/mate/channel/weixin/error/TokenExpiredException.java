package vip.mate.channel.weixin.error;

/**
 * iLink Bot token 已失效时抛出的专用异常（RFC-024 Change 3）。
 *
 * <p>{@code WeixinChannelAdapter.pollLoop} 显式 catch 此异常并：
 * <ol>
 *   <li>把 {@code connectionState} 置为 ERROR</li>
 *   <li>跳出轮询循环，交由 {@code ChannelHealthMonitor} 的重启策略处置</li>
 *   <li>（可选）通过 RFC-017 hook bus 发 {@code channel:token_expired} 通知运维</li>
 * </ol>
 * 避免之前"泛化 RuntimeException → 无限重试 → 日志淹没但用户不知道要重扫码"的僵尸状态。</p>
 */
public final class TokenExpiredException extends RuntimeException {

    private final String operation;
    private final int httpStatus;
    private final String responseBody;

    public TokenExpiredException(String operation, int httpStatus, String responseBody) {
        super("WeChat bot_token expired (HTTP " + httpStatus + ") during " + operation);
        this.operation = operation;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public String getOperation() { return operation; }
    public int getHttpStatus()   { return httpStatus; }
    public String getResponseBody() { return responseBody; }
}
