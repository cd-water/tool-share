package com.cdwater.toolshare.shared.web;

import lombok.Getter;

/**
 * 通用业务结果码。
 *
 * <p>编码规则：成功以 A 开头，客户端错误以 C 开头，服务端错误以 S 开头。
 * 全局异常处理器按该规则把业务码映射为 HTTP 状态；未识别的代码也按
 * 前缀退化，保证新增业务码不会返回错误的状态。</p>
 *
 * <p>这里只放跨模块通用的结果码；模块特有的错误场景使用
 * {@code BizException(String, String)} 自定义 code。</p>
 */
@Getter
public enum ResultCode {

    // 成功
    /** 请求成功，HTTP 200。 */
    SUCCESS("A200", "Success"),

    // 客户端错误
    /** 参数或请求格式错误，HTTP 400。 */
    BAD_REQUEST("C400", "Bad Request"),
    /** 未登录或登录失效，HTTP 401。 */
    UNAUTHORIZED("C401", "Unauthorized"),
    /** 已登录但无权限，HTTP 403。 */
    FORBIDDEN("C403", "Forbidden"),
    /** 资源不存在，HTTP 404。 */
    NOT_FOUND("C404", "Not Found"),
    /** 当前状态冲突，如重复评价、重复提交，HTTP 409。 */
    CONFLICT("C409", "Conflict"),
    /** 请求过于频繁，HTTP 429。 */
    TOO_MANY_REQUESTS("C429", "Too Many Requests"),

    // 服务端错误
    /** 未预期的服务端错误，HTTP 500。 */
    INTERNAL_SERVER_ERROR("S500", "Internal Server Error");

    /** 业务状态码。 */
    private final String code;

    /** 默认提示信息。 */
    private final String message;

    /**
     * 构造结果码。
     *
     * @param code    业务状态码
     * @param message 默认提示
     */
    ResultCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
