package com.cdwater.toolshare.ai.service.impl;

import com.cdwater.toolshare.ai.dto.ChatDTO;
import com.cdwater.toolshare.ai.entity.KbDocument;
import com.cdwater.toolshare.ai.service.AiService;
import com.cdwater.toolshare.ai.vo.ChatVO;
import com.cdwater.toolshare.common.dto.PageQuery;
import com.cdwater.toolshare.common.result.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 问答业务实现（空实现，待填充）
 */
@Service
public class AiServiceImpl implements AiService {

    @Override
    public KbDocument uploadDocument(MultipartFile file) {
        return null;
    }

    @Override
    public PageResult<KbDocument> documents(PageQuery query) {
        return null;
    }

    @Override
    public void deleteDocument(Long id) {
    }

    @Override
    public ChatVO chat(ChatDTO dto) {
        return null;
    }
}
