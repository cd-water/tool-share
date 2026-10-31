package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.service.StorageService;
import com.cdwater.toolshare.vo.FileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class UploadController {

    private final StorageService storageService;

    @PostMapping("/api/user/upload")
    public Result<FileVO> uploadUser(@RequestParam("file") MultipartFile file) {
        return Result.success(storageService.upload(file));
    }

    @PostMapping("/api/admin/upload")
    public Result<FileVO> uploadAdmin(@RequestParam("file") MultipartFile file) {
        return Result.success(storageService.upload(file));
    }
}
