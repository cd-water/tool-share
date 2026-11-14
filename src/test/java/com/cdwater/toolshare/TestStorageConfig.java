package com.cdwater.toolshare;

import com.cdwater.toolshare.shared.sequence.SequenceService;
import com.cdwater.toolshare.shared.storage.StorageService;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试环境基础设施配置。
 * <p>
 * 测试范围：为集成测试提供内存对象存储与确定性种子数据。
 * 依赖环境：Spring 测试上下文、测试数据库与类路径下的 db/test-seed.sql。
 * 关键不变量：测试不依赖 MinIO 等外部存储；种子只在空库写入一次；
 * 写完种子后重新校准 Redis 序列，保证新建业务编号不与种子冲突。
 */
@TestConfiguration
public class TestStorageConfig {

    /**
     * 以 @Primary 注册内存存储，覆盖生产环境的对象存储实现。
     * 断言目标：测试上下文中的 StorageService 注入的是内存版本。
     */
    // 以 @Primary 注册，确保测试上下文优先注入内存实现。
    // 生产对象存储 Bean 仍然存在，但不会被测试用例使用。
    @Bean
    @Primary
    StorageService inMemoryStorageService() {
        // 返回纯内存的 StorageService 子类；
        // 上传、读取与直链生成测试都在进程内完成。
        // 不依赖 MinIO 容器或网络端口。
        // 返回内存实现实例；@Primary 保证测试注入到的是它。
        // 该类无外部依赖，构造后即可直接使用。
        return new InMemoryStorageService();
    }

    /**
     * 启动时写入测试种子数据。
     * 前置条件：仅在 t_user 为空时执行，避免重复启动污染数据。
     * 执行内容：运行 test-seed.sql，并在写入后按库内最大编号重置 Redis 序列。
     */
    // 启动期 Runner 负责准备测试种子数据。
    // 使用最高优先级，保证依赖种子数据的测试先看到完整数据。
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner seedTestData(DataSource dataSource, JdbcTemplate jdbcTemplate,
                                   SequenceService sequenceService) {
        return args -> {
            // 先检查 t_user 是否已有数据；
            // 只有空库才写入种子，避免多次启动导致重复插入。
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_user", Integer.class);
            // 已有数据时直接返回，
            // 这条早退分支让 Runner 在容器复用时保持幂等。
            if (count != null && count > 0) {
                return;
            }
            // 借用一个数据库连接执行类路径下的 test-seed.sql；
            // try-with-resources 确保脚本执行后连接及时归还。
            try (Connection connection = dataSource.getConnection()) {
                // ScriptUtils 按分号拆分并逐条执行脚本，
                // 种子 SQL 与生产迁移分离，迁移本身不包含演示数据。
                // 脚本包含测试所需的最小种子数据；
                // 与生产迁移分离，避免演示数据进入正式库。
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/test-seed.sql"));
            }
            // seed 在应用启动后写入，需要按库内最大编号重新校准 Redis 序号
            // 种子写入后，按库内现有最大编号重新校准 Redis 序列；
            // 否则新建工具或租赁单可能生成与种子冲突的编号。
            // 重新校准后，新建业务实体的编号会从种子最大值继续递增。
            // 这一步失败会让后续集成测试出现主键冲突。
            sequenceService.initSequence();
        };
    }
}

/**
 * 内存对象存储实现。
 * <p>
 * 测试范围：替代真实 StorageService，供上传、读取与 URL 生成测试使用。
 * 关键不变量：写入内容做防御性拷贝、读取不存在键抛 NOT_FOUND；
 * 公共 URL 使用固定测试域名，便于断言直链格式。
 */
class InMemoryStorageService extends StorageService {

    // 固定测试域名前缀，
    // 用例可以直接断言生成的公共 URL 字符串。
    // 不依赖真实 CDN 或桶配置。
    /** 固定测试域名前缀，测试可直接断言生成的直链。 */
    // 所有测试直链都以该前缀开头；
    // 修改这里会同步影响 URL 断言。
    private static final String PUBLIC_PREFIX = "https://files.test/tool-share/";

    /** 并发安全的内存对象表，键为对象 key。 */
    // 并发安全的内存对象表，键为对象 key；
    // 集成测试可能并行执行，ConcurrentHashMap 避免竞争。
    // 值同时保存字节内容与 content type。
    private final Map<String, Stored> objects = new ConcurrentHashMap<>();

    InMemoryStorageService() {
        // 父类 StorageService 需要配置对象，但内存实现全部忽略；
        // 传入 null 即可，避免为测试复制生产配置。
        // 父类构造参数用于生产存储客户端，内存实现完全忽略。
        // 传 null 可以避免测试复制生产配置。
        super(null, null);
    }

    /**
     * 读取对象内容。
     * 前置条件：key 必须已通过 put 写入。
     * 异常语义：不存在时抛 NOT_FOUND 业务异常，与生产实现保持一致。
     */
    @Override
    public FileContent read(String key) {
        // 按 key 查找对象；
        // 找不到时抛业务 NOT_FOUND，错误语义与生产实现保持一致。
        Stored stored = objects.get(key);
        // 缺失对象必须走业务异常而不是返回 null，
        // 这样上层读取逻辑无需额外判空。
        if (stored == null) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
        // 返回保存的字节内容与类型；
        // 调用方拿到的是存储时的副本，不会影响内部数据。
        // 将内部值对象转换为 StorageService 约定的返回结构。
        // 字节数组与内容类型原样返回，便于上层直接写出响应。
        return new FileContent(stored.bytes(), stored.contentType());
    }

    /** 生成对象的公共访问地址，使用固定测试域名前缀。 */
    @Override
    public String url(String key) {
        // 生成公共访问直链：固定前缀加对象 key。
        // 测试只关心格式，不实际发起 HTTP 请求。
        return PUBLIC_PREFIX + key;
    }

    /** 返回测试环境公共 URL 前缀，供直链拼接逻辑使用。 */
    @Override
    public String publicUrlPrefix() {
        // 返回公共 URL 前缀，供需要自行拼接直链的逻辑使用。
        // 与 url(key) 使用同一常量，保证两种方式结果一致。
        return PUBLIC_PREFIX;
    }

    /**
     * 写入对象。
     * 防御性拷贝字节数组，避免调用方后续修改影响已存内容；
     * 空 contentType 回退为 application/octet-stream。
     */
    @Override
    public void put(String key, byte[] bytes, String contentType) {
        // 写入对象时对字节数组做防御性拷贝，
        // 调用方后续修改原数组不会污染已存内容。
        // 以对象 key 为索引保存内容；相同 key 会覆盖旧值，
        // 与对象存储“同一路径覆盖写”的语义一致。
        objects.put(key, new Stored(bytes.clone(),
                // contentType 为空时回退为二进制流类型；
                // 与生产存储的默认行为保持一致，避免测试掩盖差异。
                // 判断调用方是否提供了有效的内容类型；
                // 空值回退到二进制流，避免生成非法响应头。
                StringUtils.hasText(contentType) ? contentType : "application/octet-stream"));
    }

    /** 判断对象是否存在，供上传去重与读取前置校验使用。 */
    @Override
    public boolean exists(String key) {
        // 判断对象是否存在；
        // 供上传去重与读取前置校验使用，不触碰真实存储。
        // 只检查键是否存在，不读取内容；
        // 这与生产实现的存在性判断保持同一语义。
        return objects.containsKey(key);
    }

    /** 内存中保存的对象内容与类型。 */
    // 内部值对象：同时保存字节内容与 content type。
    // 使用 record 简化不可变数据的定义。
    // 不可变快照，避免测试过程中内容被意外修改。
    // record 自动提供访问器，读取路径足够简洁。
    private record Stored(byte[] bytes, String contentType) {
    }
}
