package com.cdwater.toolshare.shared.storage;

import com.cdwater.toolshare.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicFileController {

    private final StorageService storageService;

    @GetMapping("/api/files/{key}")
    public ResponseEntity<byte[]> read(@PathVariable String key) {
        StorageService.FileContent content = storageService.read(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.bytes());
    }
}
