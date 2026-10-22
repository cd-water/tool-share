package com.cdwater.toolshare.ai.service;

import com.cdwater.toolshare.ai.dto.ChatDTO;
import com.cdwater.toolshare.ai.entity.KbDocument;
import com.cdwater.toolshare.ai.vo.ChatVO;
import com.cdwater.toolshare.common.dto.PageQuery;
import com.cdwater.toolshare.common.result.PageResult;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

/**
 * AI 问答业务接口
 */
public interface AiService {

    KbDocument uploadDocument(MultipartFile file);

    PageResult<KbDocument> documents(PageQuery query);

    void deleteDocument(Long id);

    ChatVO chat(ChatDTO dto);

    /**
     * 流式问答（SSE）：sources → delta* → done，异常发 error 帧
     */
    Flux<ServerSentEvent<String>> chatStream(ChatDTO dto);
}
