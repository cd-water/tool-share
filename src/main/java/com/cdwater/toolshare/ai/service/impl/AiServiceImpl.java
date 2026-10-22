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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
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
        return list.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
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
