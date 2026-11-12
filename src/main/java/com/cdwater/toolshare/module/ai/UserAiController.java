package com.cdwater.toolshare.module.ai;

import com.cdwater.toolshare.shared.web.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/user/ai")
@RequiredArgsConstructor
public class UserAiController {

    private final AiService aiService;
    private final ToolRecommendService toolRecommendService;
    private final ObjectMapper objectMapper;

    @PostMapping("/chat")
    public Result<AiChatVO> chat(@Valid @RequestBody AiChatDTO dto) {
        return Result.success(aiService.chat(dto.getQuestion()));
    }

    /** SSE 流式问答：先发 sources 事件，再逐条发 message 事件推送回答增量。 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody AiChatDTO dto) {
        List<AiChatVO.SourceVO> sources = aiService.retrieve(dto.getQuestion());
        Flux<ServerSentEvent<String>> sourcesEvent = Flux.just(ServerSentEvent.<String>builder()
                .event("sources")
                .data(sourcesPayload(sources))
                .build());
        Flux<ServerSentEvent<String>> messageEvents = aiService
                .streamAnswer(dto.getQuestion(), sources)
                .map(delta -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(delta)
                        .build());
        return sourcesEvent.concatWith(messageEvents);
    }

    /** AI 工具推荐：向量召回 + 数据库过滤 + 大模型重排 */
    @PostMapping("/tool-recommend")
    public Result<ToolRecommendVO> toolRecommend(@Valid @RequestBody ToolRecommendDTO dto) {
        return Result.success(toolRecommendService.recommend(dto));
    }

    /** 点击推荐卡片埋点 */
    @PostMapping("/tool-recommend/{id}/click")
    public Result<Void> toolRecommendClick(@PathVariable Long id,
                                           @Valid @RequestBody ToolRecommendClickDTO dto) {
        toolRecommendService.click(id, dto.getToolId());
        return Result.success();
    }

    /** 推荐结果反馈：1 有帮助 2 没帮助 */
    @PostMapping("/tool-recommend/{id}/feedback")
    public Result<Void> toolRecommendFeedback(@PathVariable Long id,
                                              @Valid @RequestBody ToolRecommendFeedbackDTO dto) {
        toolRecommendService.feedback(id, dto.getFeedback());
        return Result.success();
    }

    private String sourcesPayload(List<AiChatVO.SourceVO> sources) {
        try {
            return objectMapper.writeValueAsString(Map.of("sources", sources));
        } catch (Exception e) {
            log.warn("来源事件序列化失败: {}", e.getMessage());
            return "{\"sources\":[]}";
        }
    }
}
