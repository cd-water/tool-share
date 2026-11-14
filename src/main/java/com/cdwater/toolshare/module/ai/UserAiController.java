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

/**
 * 居民端 AI 控制器：知识库问答与工具推荐。
 * <p>问答提供同步与 SSE 流式两种形式；工具推荐提供推荐、点击埋点与
 * 反馈三类接口。所有接口都挂载在 {@code /api/user/ai} 下，居民身份
 * 取自会话，不接受请求参数指定用户。
 * <p>流式问答使用标准 SSE：先发送 sources 事件，再逐条发送 message
 * <p>同步问答适合短问题与普通网络环境；流式问答适合长回答，前端可
 * 逐步渲染。两者共用同一套检索与兜底逻辑，只是输出方式不同。
 * <p>SSE 事件约定：
 * <ul>
 *   <li>event: sources，data 为 {"sources":[...]}；</li>
 *   <li>event: message，data 为回答增量文本；</li>
 *   <li>错误不会以 event:error 形式发送，而是转为兜底回答。</li>
 *   <li>来源事件中的片段只用于展示，不包含完整文档正文。</li>
 *   <li>工具推荐在外部 AI 异常时返回降级结果，不抛 5xx。</li>
 *   <li>所有接口的居民身份均来自会话，不接受请求参数。</li>
 *   <li>同步与流式接口共用同一套检索与兜底逻辑。</li>
 *   <li>客户端断开时由 Reactor 自动取消上游模型调用。</li>
 *   <li>流正常结束表示回答完成，客户端据此关闭连接。</li>
 * </ul>
 * 事件；工具推荐在外部 AI 异常时返回降级结果，不向前端抛错。
 */
@Slf4j
@RestController
@RequestMapping("/api/user/ai")
@RequiredArgsConstructor
public class UserAiController {

    /** 知识库问答服务。 */
    private final AiService aiService;
    /** AI 工具推荐服务。 */
    private final ToolRecommendService toolRecommendService;
    /** 用于序列化 SSE 的来源事件数据。 */
    private final ObjectMapper objectMapper;

    /**
     * 同步知识库问答。
     * <p>先检索知识库来源，再调用大模型生成回答；模型不可用时返回知识库
     * 兜底文案，接口始终返回 200 与可展示的回答。
     *
     * @param dto 问题参数
     * <p>回答保证非空；模型不可用时返回知识库兜底文案。
     * @return 回答与参考来源
     */
    @PostMapping("/chat")
    public Result<AiChatVO> chat(@Valid @RequestBody AiChatDTO dto) {
        // 同步接口直接返回完整回答与来源。
        return Result.success(aiService.chat(dto.getQuestion()));
    }

    /** SSE 流式问答：先发 sources 事件，再逐条发 message 事件推送回答增量。 */
    // 来源事件只发送一次，随后所有 message 事件都属于回答正文。
    // 前端可以先渲染来源区域，再逐步追加回答正文。
    // 即使检索为空也会发送 sources 事件，保持事件序列稳定。
    // 流式接口先固定发送 sources 事件，让前端可以立即渲染来源区域。
    // 随后转发大模型的增量文本；模型失败时由服务层输出兜底文案并正常结束。
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody AiChatDTO dto) {
        // 检索在建立事件流之前完成，便于先发送来源事件。
        List<AiChatVO.SourceVO> sources = aiService.retrieve(dto.getQuestion());
        // 固定先发来源事件，前端可据此先渲染来源列表。
        Flux<ServerSentEvent<String>> sourcesEvent = Flux.just(ServerSentEvent.<String>builder()
                .event("sources")
                .data(sourcesPayload(sources))
                .build());
        // 把每个回答增量包装为独立的 message 事件。
        Flux<ServerSentEvent<String>> messageEvents = aiService
                .streamAnswer(dto.getQuestion(), sources)
                .map(delta -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(delta)
                        .build());
        // 严格保证 sources 事件先于任何 message 事件。
        return sourcesEvent.concatWith(messageEvents);
    }

    /** AI 工具推荐：向量召回 + 数据库过滤 + 大模型重排 */
    // 返回的 recommendationId 用于后续点击与反馈，可能为空。
    // 推荐接口在内部完成召回、过滤与重排，异常时自动降级，不向前端抛错。
    @PostMapping("/tool-recommend")
    public Result<ToolRecommendVO> toolRecommend(@Valid @RequestBody ToolRecommendDTO dto) {
        // 推荐服务内部已处理降级，不会把外部异常抛给前端。
        return Result.success(toolRecommendService.recommend(dto));
    }

    /** 点击推荐卡片埋点 */
    // 埋点失败不应阻塞居民继续浏览，因此接口只返回成功或业务错误。
    // 点击埋点只记录推荐记录中的工具，服务层会校验归属与命中范围。
    @PostMapping("/tool-recommend/{id}/click")
    public Result<Void> toolRecommendClick(@PathVariable Long id,
                                           @Valid @RequestBody ToolRecommendClickDTO dto) {
        // 服务层校验推荐记录归属与工具是否在推荐结果中。
        toolRecommendService.click(id, dto.getToolId());
        return Result.success();
    }

    /** 推荐结果反馈：1 有帮助 2 没帮助 */
    // 反馈可重复提交，以最后一次为准。
    // 反馈只允许 1 或 2，用于后续推荐质量统计。
    @PostMapping("/tool-recommend/{id}/feedback")
    public Result<Void> toolRecommendFeedback(@PathVariable Long id,
                                              @Valid @RequestBody ToolRecommendFeedbackDTO dto) {
        // 服务层校验反馈值与推荐记录归属。
        toolRecommendService.feedback(id, dto.getFeedback());
        return Result.success();
    }

    /**
     * 序列化 SSE 的来源事件数据。
     * <p>序列化失败时返回空来源数组，保证事件流仍能继续，不因一个事件
     * 的序列化问题中断整个回答。
     *
     * @param sources 知识来源列表
     * <p>该事件只发送一次，不参与回答正文的增量拼接。
     * <p>序列化失败时返回空数组 JSON，保证 SSE 事件仍然合法。
     * @return JSON 字符串，形如 {"sources":[...]}
     */
    private String sourcesPayload(List<AiChatVO.SourceVO> sources) {
        try {
            // 来源列表为空时同样序列化为空数组。
            return objectMapper.writeValueAsString(Map.of("sources", sources));
        } catch (Exception e) {
            log.warn("来源事件序列化失败: {}", e.getMessage());
            // 兜底响应保持与正常事件相同的结构。
            return "{\"sources\":[]}";
        }
    }
}
