package com.cdwater.toolshare.shared.seed;

import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocMapper;
import com.cdwater.toolshare.shared.sequence.SequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 空库启动时写入演示数据：
 * <ol>
 *   <li>执行 seed/demo-data.sql，写入用户、管理员与工具；</li>
 *   <li>把 seed/knowledge/*.md 写入知识库，随后由 KnowledgeService 建立向量索引；</li>
 *   <li>校准 Redis 单号序列，避免后续新建工具/租赁单与种子编号冲突。</li>
 * </ol>
 * 图片由 {@link com.cdwater.toolshare.shared.storage.SeedAssetInitializer} 在数据写入后上传并回填。
 * 只在演示开关打开且相关表为空时执行，重复启动不会重复写入。
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "toolshare.demo", name = "seed-enabled",
        havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final SequenceService sequenceService;

    @Value("classpath:seed/demo-data.sql")
    private Resource demoData;

    @Value("classpath*:seed/knowledge/*.md")
    private Resource[] knowledgeDocs;

    @Override
    public void run(ApplicationArguments args) {
        seedRelationalData();
        seedKnowledgeDocs();
        sequenceService.initSequence();
    }

    private void seedRelationalData() {
        if (count("t_user") > 0 || count("t_tool") > 0 || count("t_admin") > 0) {
            log.info("已有初始化数据，跳过演示数据 SQL");
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(demoData, StandardCharsets.UTF_8));
            log.info("演示数据 SQL 已执行：用户、管理员与工具");
        } catch (Exception e) {
            log.error("演示数据 SQL 执行失败，请检查 seed/demo-data.sql", e);
        }
    }

    private void seedKnowledgeDocs() {
        if (count("t_knowledge_doc") > 0) {
            log.info("已有知识库文档，跳过知识库语料初始化");
            return;
        }
        Resource[] sorted = Arrays.copyOf(knowledgeDocs, knowledgeDocs.length);
        Arrays.sort(sorted, Comparator.comparing(r -> String.valueOf(r.getFilename())));
        int inserted = 0;
        for (Resource resource : sorted) {
            try {
                String fileName = resource.getFilename() == null ? "知识库文档.md" : resource.getFilename();
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n").trim();
                if (content.isEmpty()) {
                    continue;
                }
                KnowledgeDoc doc = new KnowledgeDoc();
                doc.setTitle(titleOf(content, fileName));
                doc.setFileName(fileName);
                doc.setContent(content);
                doc.setIndexed(false);
                knowledgeDocMapper.insert(doc);
                inserted++;
            } catch (IOException e) {
                log.warn("知识库语料读取失败: {}", resource.getFilename(), e);
            }
        }
        log.info("知识库语料已写入 {} 篇", inserted);
    }

    private String titleOf(String content, String fileName) {
        for (String line : content.split("\n")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return fileName.replaceFirst("\\.[^.]+$", "");
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
