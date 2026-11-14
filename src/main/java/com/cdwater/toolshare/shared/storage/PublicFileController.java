package com.cdwater.toolshare.shared.storage;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 对象存储的后端代理读取入口。
 *
 * <p>当桶公开读不可用时，文件 URL 会回退到 {@code /api/files/**}，由本
 * 控制器从存储读取并回传。它是公开路径，不在 Sa-Token 的鉴权范围内，
 * 因此必须自行保证响应头安全：只回显白名单内的图片类型，并统一加
 * {@code X-Content-Type-Options: nosniff}，防止浏览器把文件当作 HTML 执行。</p>
 *
 * <p>key 允许包含斜杠，用于种子资源的 {@code seed/tools/xxx.jpg} 分层路径。
 * 读取失败由存储服务转换为业务异常，再由全局异常处理器输出。</p>
 *
 * <p>为什么需要这个代理：对象存储桶可能不允许匿名读，或者部署时只暴露
 * 后端端口。代理接口保证在这些环境下图片仍可访问，同时由后端统一控制
 * 响应头，避免把存储返回的任意 Content-Type 直接透传给浏览器。</p>
 *
 * <p>该路径是公开的，不校验登录态，因此 key 本身不应包含敏感信息。
 * 用户上传文件的 key 带随机 UUID 前缀，难以被枚举。</p>
 */
@RestController
@RequiredArgsConstructor
public class PublicFileController {

    /** 文件代理路径前缀，用于从请求 URI 中截取对象 key。 */
    private static final String PREFIX = "/api/files/";

    /** 只回显服务端可确认安全的图片类型；历史遗留的 text/html、image/svg+xml 一律降级为下载。 */
    private static final Set<String> SAFE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    /** 存储服务，负责按 key 读取对象字节与实际 Content-Type。 */
    private final StorageService storageService;

    /** key 允许包含 /（种子资源使用 seed/tools/xxx.jpg 这类分层路径） */
    /*
     * <p>按 URI 中的对象 key 读取文件，因此使用通配路径而不是单个路径变量。
     * 返回类型固定为字节数组，
     * 不返回流，便于统一设置安全响应头。</p>
     *
     * @param request 当前 HTTP 请求，用于提取原始 URI
     * @return 带安全响应头的图片字节
     */
    @GetMapping("/api/files/**")
    public ResponseEntity<byte[]> read(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 从固定前缀之后截取 key，保留后续所有斜杠。
        int index = uri.indexOf(PREFIX);
        String key = index >= 0 ? uri.substring(index + PREFIX.length()) : "";
        // 请求 URI 可能被 URL 编码，解码后再交给存储服务。
        key = URLDecoder.decode(key, StandardCharsets.UTF_8);
        StorageService.FileContent content = storageService.read(key);
        // 非白名单类型不直接回显，降级为二进制下载，避免内容嗅探攻击。
        String contentType = SAFE_CONTENT_TYPES.contains(content.contentType())
                ? content.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                // nosniff 阻止浏览器根据内容猜测类型，是代理文件接口的必要防护。
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content.bytes());
    }
}
