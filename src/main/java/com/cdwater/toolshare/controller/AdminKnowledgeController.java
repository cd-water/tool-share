package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.dto.AiChatDTO;
import com.cdwater.toolshare.service.AiService;
import com.cdwater.toolshare.service.KnowledgeService;
import com.cdwater.toolshare.vo.AiChatVO;
import com.cdwater.toolshare.vo.KnowledgeDocVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private final KnowledgeService knowledgeService;
    private final AiService aiService;

    @GetMapping("/knowledge")
    public Result<List<KnowledgeDocVO>> list() {
        return Result.success(knowledgeService.list());
    }

    @PostMapping("/knowledge")
    public Result<KnowledgeDocVO> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "title", required = false) String title) {
        return Result.success(knowledgeService.upload(file, title));
    }

    @DeleteMapping("/knowledge/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return Result.success();
    }

    @PostMapping("/ai/chat")
    public Result<AiChatVO> chat(@Valid @RequestBody AiChatDTO dto) {
        return Result.success(aiService.chat(dto.getQuestion()));
    }
}
