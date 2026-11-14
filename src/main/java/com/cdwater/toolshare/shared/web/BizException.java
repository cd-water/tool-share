package com.cdwater.toolshare.shared.web;

import lombok.Getter;

/**
 * 可预期的业务异常。
 *
 * <p>与系统异常不同，业务异常的 code 与 message 会原样返回给调用方，
 * 由 {@link GlobalExceptionHandler} 映射为语义化 HTTP 状态。业务代码
 * 通过抛出本异常来表达“请求合法但当前状态下不允许”的情况。</p>
 *
 * <p>异常不携带堆栈之外的调试信息；message 应面向最终用户，可直接展示。
 * 真正的系统错误应让底层异常自然抛出，由兜底处理器统一记录。</p>
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码，取值见 {@link ResultCode} 或模块自定义代码。 */
    private final String code;

    /**
     * 直接指定错误码与消息。
     *
     * @param code    业务错误码
     * @param message 面向用户的错误消息
     */
    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 使用预定义结果码的默认消息。
     *
     * @param resultCode 结果码枚举
     */
    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    /**
     * 使用预定义结果码，但覆盖消息。
     *
     * @param resultCode 结果码枚举
     * @param message    更具体的用户提示
     */
    public BizException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }
}
