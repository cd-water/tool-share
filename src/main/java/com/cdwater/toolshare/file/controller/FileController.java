package com.cdwater.toolshare.file.controller;

import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.file.service.FileService;
import com.cdwater.toolshare.file.vo.FileVO;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用文件接口
 */
@Validated
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /** 通用文件上传（入 MinIO，单张 ≤5MB） */
    @PostMapping("/upload")
    public Result<FileVO> upload(@RequestParam("file") @NotNull(message = "文件不能为空") MultipartFile file) {
        return Result.success(fileService.upload(file));
    }
}
