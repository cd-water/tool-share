package com.cdwater.toolshare.shared.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：HTTP 状态码语义化，body 仍是 {@link Result} envelope。
 * 业务 code 保持不变，前端可继续按 code 处理。
 *
 * <p>设计原则：HTTP 状态反映“请求处理结果的大类”，业务 code 反映具体
 * 原因。例如参数校验失败返回 422，但 body code 仍为 C400，以兼容旧
 * 客户端按 code 判断的逻辑。</p>
 *
 * <p>日志级别：可预期的客户端错误使用 warn 且不打印堆栈；未预期异常
 * 使用 error 并记录完整堆栈，便于排查。业务异常不记录日志，因为它是
 * 正常流程的一部分，记录会淹没真正的故障。</p>
 *
 * <p>所有处理器都返回 {@code ResponseEntity<Result<Void>>}，避免不同
 * 异常返回不同 JSON 结构，前端只需解析一种 envelope。</p>
 *
 * <p>HTTP 状态映射不是一一对应：参数校验失败故意返回 422 而不是 400，
 * 以便前端区分“格式错误”与“字段不合法”，同时保留 body 中的 C400
 * 兼容旧逻辑。未识别业务码按前缀退化，新增模块无需修改本类。</p>
 *
 * <p>安全考虑：不把底层异常消息直接返回给客户端，避免泄露 SQL、类名
 * 或存储路径。只有 {@link BizException} 的 message 被视为可安全展示。</p>
 *
 * <p>扩展方式：新增业务错误码只需在 {@link ResultCode} 中添加枚举，
 * 或在抛出 BizException 时使用自定义 code；本类无需修改。若新 code
 * 需要特殊 HTTP 状态，再在 {@link #statusFor(String)} 中补充映射。</p>
 *
 * <p>测试友好：{@link #statusFor(String)} 为包级静态方法，可在不启动
 * Spring 上下文的情况下直接验证映射规则。</p>
 *
 * <p>与网关的关系：若前置网关统一改写 HTTP 状态，body 中的业务 code
 * 仍是前端判断依据；因此本类始终同时设置两者，避免依赖单一信号。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理可预期的业务异常。
     *
     * <p>业务 code 与 message 都原样返回，HTTP 状态由 code 推导。
     * 不记录日志，因为业务异常是正常控制流的一部分。</p>
     *
     * @param e 业务异常
     * @return 带对应 HTTP 状态的失败响应
     */
    // 业务异常：按业务 code 映射 HTTP 状态
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        // code 与 message 原样返回，前端可直接展示 message。
        // 业务异常不打印堆栈，避免正常业务分支污染错误日志。
        // 若需要追踪，可在抛出点按需记录 debug 日志。
        // 使用 ResponseEntity 而不是直接返回 Result，才能同时控制状态码。
        return ResponseEntity.status(statusFor(e.getCode()))
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 处理未登录或登录失效。
     *
     * <p>Sa-Token 在令牌缺失、过期、被顶下线时会抛出该异常，本方法
     * 统一转换为 401，不区分具体原因，避免给攻击者提供探测信息。</p>
     *
     * @param e 未登录异常
     * @return 401 失败响应
     */
    // 401
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e) {
        // 令牌缺失、过期或无效都归为未登录。
        // 不返回 Sa-Token 的具体异常类型，避免暴露会话实现细节。
        // 前端收到 401 后应清理本地令牌并跳转登录页。
        return fail(ResultCode.UNAUTHORIZED);
    }

    /**
     * 处理角色或权限不足。
     *
     * <p>角色异常与权限异常都映射为 403。响应中不包含所需权限点，
     * 避免帮助调用方枚举系统权限模型。</p>
     *
     * @param e 角色或权限异常
     * @return 403 失败响应
     */
    // 403
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<Result<Void>> handleNotRole(Exception e) {
        // 角色或权限不足统一返回 403，不区分具体缺失项。
        return fail(ResultCode.FORBIDDEN);
    }

    /**
     * 处理未匹配的资源或路由。
     *
     * <p>Spring MVC 6 在找不到静态资源时会抛该异常；与业务 404 合并为
     * 同一种响应，前端无需区分“路由不存在”和“数据不存在”。</p>
     *
     * @param e 资源未找到异常
     * @return 404 失败响应
     */
    // 404
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        // 未匹配到静态资源或控制器时返回统一 404。
        // 该分支不记录日志，避免扫描器请求造成日志膨胀。
        // 真正的业务 404 由 BizException 携带更具体的 message。
        return fail(ResultCode.NOT_FOUND);
    }

    /**
     * 处理 Bean Validation 校验失败。
     *
     * <p>包括请求体字段校验（MethodArgumentNotValidException）与方法参数
     * 校验（ConstraintViolationException）。HTTP 返回 422，body code 仍为
     * C400，以兼容按 code 判断的旧客户端。</p>
     *
     * @param e 校验异常
     * @return 422 失败响应
     */
    // 422：参数校验失败（body code 仍为 C400，保持旧客户端兼容）
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<Result<Void>> handleValidation(Exception e) {
        // 只记录摘要，不打印完整请求体，避免日志中出现敏感字段。
        log.warn("validation failed: {}", e.getMessage());
        // 不把具体字段错误直接返回，避免泄露内部字段命名。
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Result.fail(ResultCode.BAD_REQUEST));
    }

    /**
     * 处理请求格式或用法错误。
     *
     * <p>覆盖缺参、JSON 解析失败、参数类型不匹配、multipart 上传异常、
     * 方法不支持与媒体类型不支持等。这些都属于调用方问题，统一 400。</p>
     *
     * @param e 请求格式异常
     * @return 400 失败响应
     */
    // 400：请求格式 / 用法错误
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MultipartException.class,
            MissingServletRequestPartException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        // 客户端错误只记录消息，不打印堆栈，避免日志噪音。
        log.warn("bad request: {}", e.getMessage());
        // 覆盖缺参、JSON 解析失败、类型不匹配、上传格式错误等。
        return fail(ResultCode.BAD_REQUEST);
    }

    /**
     * 兜底处理所有未预期异常。
     *
     * <p>记录完整堆栈用于排查，但对外只返回通用 500，不泄露异常类型、
     * SQL 或内部路径。该方法是最后一道防线，不应被业务代码主动依赖。</p>
     *
     * @param e 未预期异常
     * @return 500 失败响应
     */
    // 兜底
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        // 未预期异常必须记录堆栈，方便定位；对外只返回通用 500。
        // 这里不区分业务模块，所有漏网异常都走同一出口。
        // 告警系统可基于该 error 日志配置阈值。
        // 未预期异常必须保留完整堆栈，这是排查线上问题的唯一线索。
        log.error("unexpected error", e);
        return fail(ResultCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * 使用预定义结果码构造失败响应。
     *
     * @param resultCode 结果码枚举
     * @return 带对应 HTTP 状态的响应
     */
    private ResponseEntity<Result<Void>> fail(ResultCode resultCode) {
        // 统一入口保证所有预定义错误码的 HTTP 映射一致。
        return ResponseEntity.status(statusFor(resultCode.getCode()))
                .body(Result.fail(resultCode));
    }

    /** 业务 code → HTTP 状态；未识别的 code 按前缀退化：S 开头 500，其余 400。 */
    static HttpStatus statusFor(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            // 精确匹配已知业务码，保持与前端约定一致。
            case "C400" -> HttpStatus.BAD_REQUEST;
            case "C401" -> HttpStatus.UNAUTHORIZED;
            case "C403" -> HttpStatus.FORBIDDEN;
            case "C404" -> HttpStatus.NOT_FOUND;
            case "C409" -> HttpStatus.CONFLICT;
            case "C429" -> HttpStatus.TOO_MANY_REQUESTS;
            case "S500" -> HttpStatus.INTERNAL_SERVER_ERROR;
            // 新增业务码无需修改本方法：按前缀判断错误大类。
            default -> code.startsWith("S") ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST;
        };
    }
}
