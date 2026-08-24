package vip.newsclaw.exception;

import lombok.Getter;
import vip.newsclaw.common.result.ResultCode;

/**
 * NewsClaw 业务异常
 *
 * @author NewsClaw Team
 */
@Getter
public class NewsClawException extends RuntimeException {

    private final int code;
    /** i18n message key (optional). When set, GlobalExceptionHandler uses this to look up translated message. */
    private final String msgKey;

    public NewsClawException(String message) {
        super(message);
        this.code = 500;
        this.msgKey = null;
    }

    public NewsClawException(int code, String message) {
        super(message);
        this.code = code;
        this.msgKey = null;
    }

    public NewsClawException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.code = resultCode.getCode();
        this.msgKey = null;
    }

    /**
     * Create with i18n message key. The key is resolved by GlobalExceptionHandler.
     * @param msgKey i18n key (e.g. "err.workspace.not_found")
     * @param message fallback message (Chinese or default text)
     */
    public NewsClawException(String msgKey, String message) {
        super(message);
        this.code = 500;
        this.msgKey = msgKey;
    }

    /**
     * Create with i18n message key and custom code.
     */
    public NewsClawException(String msgKey, int code, String message) {
        super(message);
        this.code = code;
        this.msgKey = msgKey;
    }
}
