package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.dto.AiChatDTO;
import com.cdwater.toolshare.service.AiService;
import com.cdwater.toolshare.vo.AiChatVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/ai")
@RequiredArgsConstructor
public class UserAiController {

    private final AiService aiService;

    @PostMapping("/chat")
    public Result<AiChatVO> chat(@Valid @RequestBody AiChatDTO dto) {
        return Result.success(aiService.chat(dto.getQuestion()));
    }
}
