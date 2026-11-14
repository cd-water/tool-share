package com.cdwater.toolshare;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基座：自动启动 MySQL 与 Redis，MinIO 用内存实现，Spring AI 自动配置全部排除。
 * 只需本机 Docker，无需手动启动任何中间件。
 */
public abstract class AbstractIntegrationTest {

    private static final String REDIS_PASSWORD = "toolredis";

    /** 排除会创建外部 AI 客户端 / 向量库连接的自动配置，避免测试访问网络。 */
    private static final String EXCLUDED_AUTOCONFIGURATIONS = String.join(",",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
            "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration");

    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.0.46"))
            .withDatabaseName("tool_share")
            .withUsername("test")
            .withPassword("test");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.11"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    static {
        MYSQL.start();
        REDIS.start();
    }

    /** 测试环境注入身份证 AES 密钥（生产环境由 ID_CARD_AES_KEY 提供，无默认值）。 */
    static final String TEST_ID_CARD_KEY = "0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void testcontainersProperties(DynamicPropertyRegistry registry) {
        registry.add("security.id-card-key", () -> TEST_ID_CARD_KEY);
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        registry.add("spring.data.redis.database", () -> 0);

        registry.add("spring.autoconfigure.exclude", () -> EXCLUDED_AUTOCONFIGURATIONS);
        registry.add("toolshare.ai.recommend.index-on-startup", () -> false);
    }
}
