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

/** 测试用依赖替换：MinIO 换为内存实现，上传/读取测试不依赖对象存储。 */
@TestConfiguration
public class TestStorageConfig {

    @Bean
    @Primary
    StorageService inMemoryStorageService() {
        return new InMemoryStorageService();
    }

    /** 测试库为空时写入测试专用 seed；迁移本身不再包含任何演示数据。 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner seedTestData(DataSource dataSource, JdbcTemplate jdbcTemplate,
                                   SequenceService sequenceService) {
        return args -> {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_user", Integer.class);
            if (count != null && count > 0) {
                return;
            }
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/test-seed.sql"));
            }
            // seed 在应用启动后写入，需要按库内最大编号重新校准 Redis 序号
            sequenceService.initSequence();
        };
    }
}

class InMemoryStorageService extends StorageService {

    private static final String PUBLIC_PREFIX = "https://files.test/tool-share/";

    private final Map<String, Stored> objects = new ConcurrentHashMap<>();

    InMemoryStorageService() {
        super(null, null);
    }

    @Override
    public FileContent read(String key) {
        Stored stored = objects.get(key);
        if (stored == null) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
        return new FileContent(stored.bytes(), stored.contentType());
    }

    @Override
    public String url(String key) {
        return PUBLIC_PREFIX + key;
    }

    @Override
    public String publicUrlPrefix() {
        return PUBLIC_PREFIX;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, new Stored(bytes.clone(),
                StringUtils.hasText(contentType) ? contentType : "application/octet-stream"));
    }

    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    private record Stored(byte[] bytes, String contentType) {
    }
}
