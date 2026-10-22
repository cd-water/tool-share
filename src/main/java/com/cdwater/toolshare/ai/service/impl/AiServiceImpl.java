package com.cdwater.toolshare.ai.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.ai.dto.ChatDTO;
import com.cdwater.toolshare.ai.entity.KbDocument;
import com.cdwater.toolshare.ai.mapper.KbDocumentMapper;
import com.cdwater.toolshare.ai.service.AiService;
import com.cdwater.toolshare.ai.vo.ChatSourceVO;
import com.cdwater.toolshare.ai.vo.ChatVO;
import com.cdwater.toolshare.common.dto.PageQuery;
import com.cdwater.toolshare.common.enums.ResultCode;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.core.sync.RequestBody;
import tools.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 问答业务实现（RAG：MinIO 存档 → Tika 解析 → 切分 → Qdrant）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private static final int STATUS_PARSING = 1;
    private static final int STATUS_COMPLETED = 2;
    private static final int STATUS_FAILED = 3;
    private static final int TOP_K = 5;

    private final KbDocumentMapper documentMapper;
    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${minio.endpoint}")
    private String endpoint;

    @Override
    public KbDocument uploadDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "文件不能为空");
        }
        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "document";
        String objectKey = "kb/" + UUID.randomUUID() + "-" + safeName(fileName);

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (Exception e) {
            log.error("知识库文档上传 MinIO 失败: {}", fileName, e);
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "文档上传失败");
        }

        KbDocument document = new KbDocument();
        document.setFileName(fileName);
        document.setFileUrl(fileUrlOf(objectKey));
        document.setFileSize(file.getSize());
        document.setStatus(STATUS_PARSING);
        document.setUploadBy(StpUtil.getLoginIdAsLong());
        documentMapper.insert(document);

        try {
            List<Document> parsed = new TikaDocumentReader(
                    new InputStreamResource(file.getInputStream())).get();
            List<Document> chunks = TokenTextSplitter.builder().build().apply(parsed).stream()
                    .map(chunk -> new Document(chunk.getText(), metadataOf(document.getId(), fileName)))
                    .toList();

            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
            }
            document.setChunkCount(chunks.size());
            document.setStatus(STATUS_COMPLETED);
            documentMapper.updateById(document);
            return document;
        } catch (Exception e) {
            log.error("知识库文档解析入库失败: {}", fileName, e);
            document.setStatus(STATUS_FAILED);
            documentMapper.updateById(document);
            throw new BizException(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "文档解析失败");
        }
    }

    @Override
    public PageResult<KbDocument> documents(PageQuery query) {
        Page<KbDocument> page = documentMapper.selectPage(query.toPage(),
                new LambdaQueryWrapper<KbDocument>()
                        .orderByDesc(KbDocument::getCreateTime)
                        .orderByDesc(KbDocument::getId));
        return PageResult.of(page);
    }

    @Override
    public void deleteDocument(Long id) {
        KbDocument document = documentMapper.selectById(id);
        if (document == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "文档不存在");
        }

        String objectKey = objectKeyOf(document.getFileUrl());
        if (objectKey != null) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build());
            } catch (S3Exception e) {
                log.warn("删除 MinIO 文档失败，key={}", objectKey, e);
            }
        }

        vectorStore.delete(new FilterExpressionBuilder().eq("documentId", String.valueOf(id)).build());
        documentMapper.deleteById(id);
    }

    @Override
    public ChatVO chat(ChatDTO dto) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(TOP_K)
                .similarityThresholdAll()
                .build();
        QuestionAnswerAdvisor advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .build();

        ChatClientResponse response = chatClientBuilder.build()
                .prompt()
                .user(dto.getQuestion())
                .advisors(advisor)
                .call()
                .chatClientResponse();

        ChatVO vo = new ChatVO();
        vo.setAnswer(answerOf(response));
        vo.setSources(sourcesOf(response));
        return vo;
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(ChatDTO dto) {
        return Flux.defer(() -> {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().topK(TOP_K).build());
            Flux<String> answer = chatClientBuilder.build().prompt()
                    .system(SYSTEM_TEMPLATE.formatted(contextOf(docs)))
                    .user(dto.getQuestion())
                    .stream()
                    .content();
            return Flux.concat(
                    Flux.just(frame("sources", jsonOf(toSources(docs)))),
                    answer.map(chunk -> frame("delta", jsonOf(chunk))),
                    Flux.just(frame("done", "[DONE]")));
        }).onErrorResume(e -> {
            log.error("流式问答失败: {}", dto.getQuestion(), e);
            return Flux.just(frame("error", jsonOf("回答生成失败，请稍后重试")));
        });
    }

    /**
     * 流式提示词：仅依据检索片段回答，无资料如实说明（与非流式 advisor 行为对齐）
     */
    private static final String SYSTEM_TEMPLATE = """
            你是社区共享工具租赁系统的智能助手，请依据以下知识库资料回答居民问题，
            资料中没有的内容请如实说明没有相关资料。资料：
            %s
            """;

    private ServerSentEvent<String> frame(String event, String data) {
        return ServerSentEvent.builder(data).event(event).build();
    }

    /** 检索片段拼上下文，与 toSources 同源 */
    private String contextOf(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Object fileName = doc.getMetadata().get("fileName");
            sb.append("【片段").append(i + 1)
                    .append(fileName == null ? "" : "·" + fileName)
                    .append("】\n").append(doc.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private String jsonOf(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private Map<String, Object> metadataOf(Long documentId, String fileName) {
        Map<String, Object> metadata = new HashMap<>();
        // Qdrant 存储会把 Long 元数据转成字符串，按字符串存/删才能命中过滤条件
        metadata.put("documentId", String.valueOf(documentId));
        metadata.put("fileName", fileName);
        return metadata;
    }

    private String answerOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return "";
        }
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private List<ChatSourceVO> sourcesOf(ChatClientResponse response) {
        if (response == null) {
            return List.of();
        }
        Object retrieved = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(retrieved instanceof List<?> list)) {
            return List.of();
        }
        return toSources(list.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
                .toList());
    }

    /** Document → 来源片段（流式与非流式共用） */
    private List<ChatSourceVO> toSources(List<Document> docs) {
        return docs.stream()
                .map(document -> {
                    ChatSourceVO source = new ChatSourceVO();
                    source.setContent(document.getText());
                    Object fileName = document.getMetadata().get("fileName");
                    source.setDocumentName(fileName == null ? null : fileName.toString());
                    return source;
                })
                .toList();
    }

    private String safeName(String fileName) {
        return fileName.replaceAll("[\\\\/]", "_");
    }

    private String fileUrlOf(String objectKey) {
        return normalizedEndpoint() + "/" + bucket + "/" + objectKey;
    }

    private String objectKeyOf(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            return null;
        }
        String prefix = normalizedEndpoint() + "/" + bucket + "/";
        return fileUrl.startsWith(prefix) ? fileUrl.substring(prefix.length()) : null;
    }

    private String normalizedEndpoint() {
        return endpoint.replaceAll("/+$", "");
    }
}
