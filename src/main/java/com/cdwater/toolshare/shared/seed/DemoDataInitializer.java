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
 *
 * <p>三个步骤各自检查目标表是否为空：关系数据、知识库文档与序号
 * 彼此独立，因此可以单独跳过。例如已有用户但知识库为空时，仍会
 * 导入知识库语料。</p>
 *
 * <p>演示开关由 {@code toolshare.demo.seed-enabled} 控制，缺省为 true，
 * 方便本地开箱即用；生产环境应显式关闭，避免向真实库写入演示数据。</p>
 *
 * <p>执行顺序 {@code @Order(10)} 早于图片初始化（20），因为图片回填
 * 依赖工具与用户记录已经存在。</p>
 *
 * <p>为什么用 SQL 脚本而不是 Java 组装实体：演示数据包含多表关联与
 * 固定主键，脚本更直观且便于人工调整。执行器按 UTF-8 读取，支持
 * 分号分隔的多条语句。</p>
 *
 * <p>失败策略：SQL 或语料导入失败只记录日志，不阻止应用启动。演示
 * 数据缺失不应影响核心功能与健康检查。</p>
 *
 * <p>该初始化器只在启动时运行一次，与请求线程无交互，因此无需考虑
 * 并发；多实例同时启动时依赖脚本本身的幂等判断，可能各自执行一次。</p>
 *
 * <p>可测试性：三个 private 步骤彼此独立，便于单独验证表为空与表非空
 * 两条路径。脚本执行与语料读取都不依赖 Web 上下文。</p>
 *
 * <p>日志约定：跳过与成功使用 info，脚本失败使用 error，单文件读取
 * 失败使用 warn。日志中不输出身份证等敏感字段。</p>
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "toolshare.demo", name = "seed-enabled",
        havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    /** 数据源，用于执行演示 SQL 脚本。 */
    private final DataSource dataSource;

    /** 轻量 JDBC 入口，用于判断表是否为空。 */
    private final JdbcTemplate jdbcTemplate;

    /** 知识库 Mapper，用于写入 markdown 语料。 */
    private final KnowledgeDocMapper knowledgeDocMapper;

    /** 序号服务，用于在建数据后校准 Redis 序列。 */
    private final SequenceService sequenceService;

    /** 演示关系数据脚本。 */
    @Value("classpath:seed/demo-data.sql")
    private Resource demoData;

    /** 知识库 markdown 语料，按文件名排序后导入。 */
    @Value("classpath*:seed/knowledge/*.md")
    private Resource[] knowledgeDocs;

    /**
     * 依次执行关系数据、知识库与序号校准。
     *
     * @param args 启动参数，本初始化器不使用
     */
    @Override
    public void run(ApplicationArguments args) {
        // 先写关系数据，再写知识库，两者互不依赖。
        seedRelationalData();
        seedKnowledgeDocs();
        // 最后校准序号，确保 Redis 起点大于数据库中已有的最大编号。
        sequenceService.initSequence();
    }

    /**
     * 仅在相关表都为空时执行演示 SQL。
     *
     * <p>判断条件使用“或”：只要用户、工具、管理员任一表有数据，就认为
     * 该环境不是空库，跳过脚本，避免覆盖真实数据。</p>
     *
     * <p>使用原始 JDBC 连接执行脚本，而不是 JdbcTemplate，是为了让
     * Spring 的 ScriptUtils 能完整控制语句分隔与错误处理。</p>
     *
     * <p>脚本执行失败不会回滚已执行的部分语句；演示环境可接受，重新
     * 初始化时应清空相关表后重试。</p>
     */
    private void seedRelationalData() {
        if (count("t_user") > 0 || count("t_tool") > 0 || count("t_admin") > 0) {
            log.info("已有初始化数据，跳过演示数据 SQL");
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            // 使用 Spring 的脚本执行器，按 UTF-8 读取并支持多语句分隔。
            // 连接由 try-with-resources 管理，异常时自动关闭。
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(demoData, StandardCharsets.UTF_8));
            log.info("演示数据 SQL 已执行：用户、管理员与工具");
            // 成功日志不包含具体账号或密码，避免敏感信息进入日志系统。
        } catch (Exception e) {
            // 演示数据失败不阻断启动，但保留完整错误日志便于修复脚本。
            log.error("演示数据 SQL 执行失败，请检查 seed/demo-data.sql", e);
        }
    }

    /**
     * 仅在知识库表为空时导入 markdown 语料。
     *
     * <p>资源按文件名排序，保证每次导入顺序一致，文档 ID 与向量索引
     * 结果可复现。文件内容去除首尾空白，空文件直接跳过。</p>
     *
     * <p>文档先以未索引状态落库，向量索引由知识库服务在后续流程统一
     * 建立；这样即使向量服务暂时不可用，语料也不会丢失。</p>
     */
    private void seedKnowledgeDocs() {
        if (count("t_knowledge_doc") > 0) {
            // 已有任意文档即视为已完成初始化，不再追加语料。
            log.info("已有知识库文档，跳过知识库语料初始化");
            return;
        }
        Resource[] sorted = Arrays.copyOf(knowledgeDocs, knowledgeDocs.length);
        // classpath 通配扫描顺序不稳定，按文件名排序保证可复现。
        // 复制数组避免修改注入字段本身，保持初始化器状态只读。
        Arrays.sort(sorted, Comparator.comparing(r -> String.valueOf(r.getFilename())));
        int inserted = 0;
        for (Resource resource : sorted) {
            try {
                String fileName = resource.getFilename() == null ? "知识库文档.md" : resource.getFilename();
                // 统一换行符并去除首尾空白，与切块器的规范化保持一致。
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n").trim();
                if (content.isEmpty()) {
                    // 空文件不产生文档，避免知识库出现无内容条目。
                    continue;
                }
                KnowledgeDoc doc = new KnowledgeDoc();
                // 优先使用 markdown 一级标题作为文档标题，没有则用文件名。
                // 标题只用于展示，不参与向量切块内容。
                doc.setTitle(titleOf(content, fileName));
                // 文件名保留扩展名，便于后续按类型筛选语料。
                doc.setFileName(fileName);
                // 内容为规范化后的 markdown 原文，向量切块直接使用。
                doc.setContent(content);
                // 先标记未索引，由后续知识库索引流程异步建立向量。
                doc.setIndexed(false);
                knowledgeDocMapper.insert(doc);
                inserted++;
            } catch (IOException e) {
                // 单个文件读取失败只跳过该文件，不影响其它语料。
                log.warn("知识库语料读取失败: {}", resource.getFilename(), e);
            }
        }
        // 只记录篇数，不逐篇打印文件名，避免启动日志过长。
        log.info("知识库语料已写入 {} 篇", inserted);
    }

    /**
     * 从 markdown 内容中提取一级标题。
     *
     * <p>只识别行首的 {@code "# "}，不解析复杂 markdown 语法；找不到时
     * 回退为去掉扩展名的文件名。</p>
     *
     * @param content  文件内容
     * @param fileName 文件名，用作兜底标题
     * @return 文档标题
     */
    private String titleOf(String content, String fileName) {
        for (String line : content.split("\n")) {
            // 只认一级标题；二级及以下标题不作为文档标题。
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        // 没有标题时去掉最后一个扩展名，保留可读文件名。
        return fileName.replaceFirst("\\.[^.]+$", "");
    }

    /**
     * 统计表行数。
     *
     * <p>表名来自本类内部固定常量，不接受外部输入，因此可以直接拼接。</p>
     *
     * @param table 表名
     * @return 行数；查询返回 null 时按 0 处理
     */
    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        // COUNT(*) 理论上不会返回 null，这里仍做兜底以简化调用方判断。
        return count == null ? 0 : count;
    }
}
