package com.cdwater.toolshare.shared.web;

import lombok.Data;

/**
 * 统一响应 envelope。
 *
 * <p>所有接口都返回 {@code code + message + data} 三段结构：code 是业务
 * 码（不是 HTTP 状态），message 是可直接展示的提示，data 是实际数据。
 * 前端通过 code 判断成功与否，HTTP 状态由全局异常处理器单独映射。</p>
 *
 * <p>构造器私有，强制通过 {@link #success()} 或 {@link #fail(String, String)}
 * 创建，避免出现 code 为空或成功码不一致的响应。</p>
 *
 * @param <T> 数据类型
 */
@Data
public class Result<T> {

    /** 业务状态码，成功为 {@code A200}，失败为 C/S 开头的业务码。 */
    private String code;

    /** 面向用户的提示信息。 */
    private String message;

    /** 实际业务数据；失败时通常为 null。 */
    private T data;

    /**
     * 禁止直接实例化，统一走静态工厂。
     */
    private Result() {
    }

    /**
     * 构造无数据的成功响应。
     *
     * @param <T> 数据类型
     * @return 成功响应，data 为 null
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 构造带数据的成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        // 成功码与消息统一取自 ResultCode，避免各接口硬编码。
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    /**
     * 构造失败响应。
     *
     * @param code    业务错误码
     * @param message 错误提示
     * @param <T>     数据类型
     * @return 失败响应，data 为 null
     */
    public static <T> Result<T> fail(String code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    /**
     * 使用预定义结果码构造失败响应。
     *
     * @param resultCode 结果码枚举
     * @param <T>        数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }
}
