package com.cdwater.toolshare.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.cdwater.toolshare.assistant.dto.ChatRequest;
import com.cdwater.toolshare.assistant.dto.ChatResponse;
import com.cdwater.toolshare.assistant.dto.KbDocCreatedResponse;
import com.cdwater.toolshare.assistant.dto.KbDocItem;
import com.cdwater.toolshare.assistant.service.AssistantService;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    @SaCheckRole("ADMIN")
    @PostMapping("/documents")
    public Result<KbDocCreatedResponse> upload(@RequestParam MultipartFile file,
                                               @RequestParam String docType,
                                               @RequestParam(required = false) Long toolId) {
        return Result.success(assistantService.upload(file, docType, toolId));
    }

    @SaCheckRole("ADMIN")
    @GetMapping("/documents")
    public Result<PageResult<KbDocItem>> page(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "10") long size) {
        return Result.success(assistantService.page(page, size));
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return Result.success(assistantService.chat(request));
    }
}
