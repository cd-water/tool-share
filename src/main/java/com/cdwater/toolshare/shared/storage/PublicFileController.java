package com.cdwater.toolshare.shared.storage;

import com.cdwater.toolshare.shared.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
public class PublicFileController {

    private static final String PREFIX = "/api/files/";

    private final StorageService storageService;

    /** key 允许包含 /（种子资源使用 seed/tools/xxx.jpg 这类分层路径） */
    @GetMapping("/api/files/**")
    public ResponseEntity<byte[]> read(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int index = uri.indexOf(PREFIX);
        String key = index >= 0 ? uri.substring(index + PREFIX.length()) : "";
        key = URLDecoder.decode(key, StandardCharsets.UTF_8);
        StorageService.FileContent content = storageService.read(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.bytes());
    }
}
