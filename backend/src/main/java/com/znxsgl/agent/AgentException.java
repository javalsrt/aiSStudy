package com.znxsgl.agent;

/**
 * AI 助手业务异常：携带可读提示与错误码，避免把底层异常直接抛给前端。
 */
public class AgentException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public AgentException(String code, String message) {
        this(code, message, 400);
    }

    public AgentException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
