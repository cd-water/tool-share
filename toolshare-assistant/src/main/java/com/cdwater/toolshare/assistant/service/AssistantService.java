package com.cdwater.toolshare.assistant.service;

import com.cdwater.toolshare.assistant.dto.ChatRequest;
import com.cdwater.toolshare.assistant.dto.ChatResponse;
import com.cdwater.toolshare.assistant.dto.KbDocCreatedResponse;
import com.cdwater.toolshare.assistant.dto.KbDocItem;
import com.cdwater.toolshare.common.result.PageResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库维护与 RAG 问答（AI 未配置时降级，见技术方案 §3.5⑦）
 */
public interface AssistantService {

    KbDocCreatedResponse upload(MultipartFile file, String docType, Long toolId);

    PageResult<KbDocItem> page(long page, long size);

    ChatResponse chat(ChatRequest request);
}
