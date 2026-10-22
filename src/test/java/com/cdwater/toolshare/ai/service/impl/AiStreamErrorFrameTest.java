package com.cdwater.toolshare.ai.service.impl;

import com.cdwater.toolshare.ai.dto.ChatDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式问答 error 帧映射保护：上游任何异常（含 90s 超时）都必须转成 event:error 帧，
 * 而不是让连接静默断流（前端依赖该帧判定失败）。
 */
class AiStreamErrorFrameTest {

    @Test
    void upstreamError_becomesErrorFrame() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.build().prompt()
                .system(anyString())
                .user(anyString())
                .stream()
                .content())
                .thenReturn(Flux.error(new RuntimeException("DeepSeek boom")));

        AiServiceImpl service = new AiServiceImpl(
                null, vectorStore, builder, null, new ObjectMapper());

        List<String> frames = service.chatStream(dto()).collectList().block()
                .stream()
                .map(event -> event.event() + "|" + event.data())
                .toList();

        assertTrue(frames.stream().anyMatch(f -> f.startsWith("sources|")), "应先有 sources 帧");
        assertTrue(frames.stream().noneMatch(f -> f.startsWith("delta|")), "不应有 delta 帧");
        assertTrue(frames.stream().anyMatch(f -> f.startsWith("error|")), "异常必须转 error 帧");
        assertTrue(frames.stream().noneMatch(f -> f.startsWith("done|")), "出错后不应有 done 帧");
    }

    private ChatDTO dto() {
        ChatDTO dto = new ChatDTO();
        dto.setQuestion("测试问题");
        return dto;
    }
}
