package com.cdwater.toolshare.web;

import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.web.dto.UploadResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用文件上传（跨模块，见 docs/API.md §8；实现时调用 common 的 FileStorageService）
 */
@RestController
@RequestMapping("/api/file")
public class FileController {

    @PostMapping("/upload")
    public Result<UploadResponse> upload(@RequestParam MultipartFile file, @RequestParam String dir) {
        // TODO 业务实现：校验类型/大小后 putObject，返回访问 URL
        return Result.success(null);
    }
}
