package com.cdwater.toolshare.assistant.service.impl;

import com.cdwater.toolshare.assistant.dto.ChatRequest;
import com.cdwater.toolshare.assistant.dto.ChatResponse;
import com.cdwater.toolshare.assistant.dto.KbDocCreatedResponse;
import com.cdwater.toolshare.assistant.dto.KbDocItem;
import com.cdwater.toolshare.assistant.service.AssistantService;
import com.cdwater.toolshare.common.result.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssistantServiceImpl implements AssistantService {

    @Override
    public KbDocCreatedResponse upload(MultipartFile file, String docType, Long toolId) {
        // TODO 业务实现：落 kb_document(PENDING) + 异步 Tika 解析切片 + embedding
        return null;
    }

    @Override
    public PageResult<KbDocItem> page(long page, long size) {
        // TODO 业务实现
        return null;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        // TODO 业务实现：pgvector 余弦 top5 → LLM 生成 → 返回答案与来源片段
        return null;
    }
}
