package com.cdwater.toolshare.shared.storage;

import com.cdwater.toolshare.shared.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class UploadController {

    private final ImageUploadProcessor imageUploadProcessor;
    private final StorageService storageService;

    @PostMapping("/api/user/upload")
    public Result<FileVO> uploadUser(@RequestParam("file") MultipartFile file) {
        return Result.success(upload(file));
    }

    @PostMapping("/api/admin/upload")
    public Result<FileVO> uploadAdmin(@RequestParam("file") MultipartFile file) {
        return Result.success(upload(file));
    }

    private FileVO upload(MultipartFile file) {
        ImageUploadProcessor.ProcessedImage image = imageUploadProcessor.process(file);
        return storageService.store(image.bytes(), image.contentType(), image.storedName());
    }
}
