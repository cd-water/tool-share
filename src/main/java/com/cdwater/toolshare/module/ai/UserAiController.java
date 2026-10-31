package com.cdwater.toolshare.module.ai;

import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.ai.AiChatDTO;
import com.cdwater.toolshare.module.ai.AiService;
import com.cdwater.toolshare.module.ai.AiChatVO;
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
