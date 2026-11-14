package com.cdwater.toolshare.shared.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局异常处理器单元测试。
 * <p>
 * 测试范围：业务异常编码到 HTTP 状态码的映射，以及未知编码的兜底规则。
 * 依赖环境：无需 Spring 容器，直接实例化 {@link GlobalExceptionHandler}。
 * 关键不变量：已知编码映射稳定；未知编码按前缀区分客户端错误与服务端错误。
 */
class GlobalExceptionHandlerTest {

    // 被测处理器无状态，直接 new 即可，不启动 Spring 容器，保持单元测试轻量。
    // 跨用例共享同一实例，断言只关注编码到 HTTP 状态码的映射本身。
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 场景：逐个输入已登记的业务错误码。
     * 前置条件：处理器对 C4xx、S500 等编码有显式映射。
     * 断言目标：400/401/403/404/409/429/500 与编码语义一一对应。
     */
    @Test
    void known_business_codes_map_to_semantic_status() {
        // 客户端错误家族逐个断言：参数、未认证、无权限、资源不存在、冲突、限流分别对应 4xx。
        // 写死期望值，避免映射表被误改后静默退化为统一错误码。
        // 示例：C400 表示请求参数不合法。
        assertEquals(400, statusOf("C400"));
        // 未登录或 token 失效统一 401，前端据此跳转登录。
        assertEquals(401, statusOf("C401"));
        // 已登录但无权限返回 403，与未认证明确区分。
        assertEquals(403, statusOf("C403"));
        assertEquals(404, statusOf("C404"));
        assertEquals(409, statusOf("C409"));
        // 限流返回 429，提示调用方稍后重试。
        assertEquals(429, statusOf("C429"));
        // 服务端错误使用 5xx，明确提示调用方这是系统问题而非请求本身有误。
        assertEquals(500, statusOf("S500"));
    }

    /**
     * 场景：输入未登记的编码以及 null 编码。
     * 前置条件：映射表中不存在 X123、S999。
     * 断言目标：X 前缀兜底为 400，S 前缀兜底为 500，null 兜底为 400。
     */
    @Test
    void unmapped_code_falls_back_by_prefix() {
        // 未登记的 X 前缀按客户端错误兜底，新增编码忘记登记时仍是可理解的 4xx。
        assertEquals(400, statusOf("X123"));
        // 未登记的 S 前缀按服务端错误兜底，便于监控区分用户输入错误与系统故障。
        assertEquals(500, statusOf("S999"));
        // 编码缺失（null）说明调用方未正确抛错，按最保守的 400 处理，避免误报 5xx。
        assertEquals(400, GlobalExceptionHandler.statusFor(null).value());
    }

    /**
     * 辅助方法：用指定编码构造业务异常并交给处理器，返回响应状态码。
     */
    // 统一经由 handleBiz 走真实处理器链路，而不是直接读映射表，
    // 这样同时覆盖异常对象构造与响应封装两个环节。
    private int statusOf(String code) {
        // BizException 携带编码与消息，处理器只读取编码决定 HTTP 状态码。
        return handler.handleBiz(new BizException(code, "test")).getStatusCode().value();
    }
}
