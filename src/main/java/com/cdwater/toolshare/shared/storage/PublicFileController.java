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

@RestController
@RequiredArgsConstructor
public class PublicFileController {

    private static final String PREFIX = "/api/files/";

    /** 只回显服务端可确认安全的图片类型；历史遗留的 text/html、image/svg+xml 一律降级为下载。 */
    private static final Set<String> SAFE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private final StorageService storageService;

    /** key 允许包含 /（种子资源使用 seed/tools/xxx.jpg 这类分层路径） */
    @GetMapping("/api/files/**")
    public ResponseEntity<byte[]> read(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int index = uri.indexOf(PREFIX);
        String key = index >= 0 ? uri.substring(index + PREFIX.length()) : "";
        key = URLDecoder.decode(key, StandardCharsets.UTF_8);
        StorageService.FileContent content = storageService.read(key);
        String contentType = SAFE_CONTENT_TYPES.contains(content.contentType())
                ? content.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content.bytes());
    }
}
