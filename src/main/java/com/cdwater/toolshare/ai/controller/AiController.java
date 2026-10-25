package com.cdwater.toolshare.ai.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.cdwater.toolshare.ai.dto.ChatDTO;
import com.cdwater.toolshare.ai.entity.KbDocument;
import com.cdwater.toolshare.ai.service.AiService;
import com.cdwater.toolshare.ai.vo.ChatVO;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.dto.PageQuery;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

/**
 * AI 问答接口（RAG 知识库）
 */
@Validated
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    /** 上传知识库文档（Tika 解析后入 Qdrant） */
    @SaCheckRole(RoleConstant.ADMIN)
    @PostMapping("/documents")
    public Result<KbDocument> uploadDocument(@RequestParam("file") @NotNull(message = "文件不能为空") MultipartFile file) {
        return Result.success(aiService.uploadDocument(file));
    }

    /** 知识库文档分页 */
    @SaCheckRole(RoleConstant.ADMIN)
    @GetMapping("/documents")
    public Result<PageResult<KbDocument>> documents(@Valid PageQuery query) {
        return Result.success(aiService.documents(query));
    }

    /** 删除知识库文档（同步删 Qdrant 向量） */
    @SaCheckRole(RoleConstant.ADMIN)
    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        aiService.deleteDocument(id);
        return Result.success();
    }

    /** RAG 问答 */
    @PostMapping("/chat")
    public Result<ChatVO> chat(@Valid @RequestBody ChatDTO dto) {
        return Result.success(aiService.chat(dto));
    }

    /** RAG 流式问答（SSE：sources → delta* → done，异常发 error 帧） */
    @PostMapping("/chat/stream")
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody ChatDTO dto) {
        return aiService.chatStream(dto);
    }
}
