package com.cdwater.toolshare;

// 集成测试运行所需的 Spring 测试上下文、Testcontainers 容器与动态属性工具。
// 业务测试类继承本基座后，无需各自重复声明数据库、缓存与外部依赖的启动方式。
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// 测试环境的三个外部依赖由基座统一提供：
// 1) MySQL：每次测试 JVM 启动时创建独立的 8.0.46 容器，建库 tool_share；
// 2) Redis：独立容器，带密码启动，供 Sa-Token 与缓存使用；
// 3) MinIO：不启动真实容器，由 TestStorageConfig 提供内存实现。
// Spring AI 的自动配置整体排除，确保测试过程不会创建外部网络连接。
// 容器使用 static + 静态初始化块，保证整个测试 JVM 只启动一次，
// 所有子类共享同一套端口与连接池，既节省时间又保持数据隔离边界。
// 动态属性在 Spring 上下文创建前注入，替代 application-test 中的固定地址。
// 该基座不定义任何 @Test，只负责提供可复用的运行环境。
// 子类通过继承获得容器与属性配置，再叠加自己的 @SpringBootTest 场景。
// 因此修改本类会影响全部集成测试，必须保持配置最小且稳定。
// 若新增外部依赖，应优先在此提供统一的测试替身，而不是让子类各自连接。
/**
 * 集成测试基座：自动启动 MySQL 与 Redis，MinIO 用内存实现，Spring AI 自动配置全部排除。
 * 只需本机 Docker，无需手动启动任何中间件。
 */
public abstract class AbstractIntegrationTest {

    // Redis 容器密码与生产配置解耦，仅用于测试容器内部鉴权。
    // 该值同时通过动态属性注入到 Spring 的 redis 连接配置。
    private static final String REDIS_PASSWORD = "toolredis";

    /** 排除会创建外部 AI 客户端 / 向量库连接的自动配置，避免测试访问网络。 */
    // 排除的自动配置覆盖 OpenAI 聊天/向量/图像/审核/语音与 Qdrant 向量库。
    // 这些组件在启动时会尝试建立远程客户端，测试环境既无凭据也不应访问网络。
    private static final String EXCLUDED_AUTOCONFIGURATIONS = String.join(",",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
            "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration");

    // MySQL 容器在整个测试 JVM 生命周期内复用：
    // 固定镜像版本避免不同开发者环境产生行为差异，
    // 数据库名、用户名与密码与动态属性注入保持一致。
    // 容器首次使用时才拉取镜像，本地 Docker 是唯一前置条件。
    // 固定 MySQL 版本，避免 CI 与本地因镜像漂移出现 SQL 方言差异。
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.0.46"))
            .withDatabaseName("tool_share")
            .withUsername("test")
            .withPassword("test");

    // Redis 容器暴露标准 6379 端口，映射端口由 Testcontainers 随机分配。
    // 启动命令要求密码，模拟生产环境对未授权访问的防护。
    // Redis 7.4 与生产版本保持一致，减少命令行为偏差。
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.11"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    // 静态初始化块在类加载时启动两个容器。
    // 放在 static 块而非 @BeforeAll，是为了确保动态属性注册时
    // 容器已经就绪、JDBC URL 与映射端口可以立即读取。
    // 容器随 JVM 退出由 Testcontainers 的 Ryuk 负责清理。
    static {
        // 容器启动是阻塞操作，静态块保证后续动态属性读取时端口已确定。
        MYSQL.start();
        REDIS.start();
    }

    // 测试专用 AES 密钥：长度 32 字符，满足身份证加密器要求。
    // 生产环境必须通过 ID_CARD_AES_KEY 注入，故意不提供默认值。
    /** 测试环境注入身份证 AES 密钥（生产环境由 ID_CARD_AES_KEY 提供，无默认值）。 */
    // 密钥只用于测试数据加密，不包含任何真实身份信息。
    // 长度与生产密钥一致，确保加解密路径被完整覆盖。
    static final String TEST_ID_CARD_KEY = "0123456789abcdef0123456789abcdef";

    // 动态属性方法的签名由 Spring 测试框架约定，不能改为实例方法。
    // 所有 registry.add 都在上下文创建前收集，供数据源与缓存客户端读取。
    // 将容器连接信息与测试密钥注册为 Spring 动态属性。
    // 这些属性在上下文刷新前生效，因此优先级高于配置文件中的静态值。
    @DynamicPropertySource
    // 数据源连接信息直接绑定容器，测试代码无需关心宿主机端口。
    // 同名属性会覆盖 application-test 中的静态配置。
    static void testcontainersProperties(DynamicPropertyRegistry registry) {
        // 身份证加密密钥使用固定测试值，保证脱敏结果可重复断言。
        registry.add("security.id-card-key", () -> TEST_ID_CARD_KEY);
        // 以下三项把数据源切换到本次测试启动的 MySQL 容器。
        // 每个测试 JVM 使用独立数据库，避免与本地开发数据互相污染。
        // JDBC URL 由容器生成，包含随机映射端口与库名。
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        // 用户名与密码与容器创建参数一致。
        registry.add("spring.datasource.username", MYSQL::getUsername);
        // 密码同样来自容器参数，避免测试配置与容器不一致。
        registry.add("spring.datasource.password", MYSQL::getPassword);

        // Redis 的主机、映射端口、密码与数据库编号全部来自容器实例。
        // 端口必须使用 getMappedPort，因为宿主机端口是随机分配的。
        // Redis 主机为容器地址，端口使用映射后的宿主机端口。
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        // 固定使用 0 号库，确保测试之间共享同一份缓存命名空间并由清理逻辑处理。
        registry.add("spring.data.redis.database", () -> 0);

        // 关闭会产生外部连接的 Spring AI 自动配置。
        // 通过 spring.autoconfigure.exclude 生效，排除列表以逗号分隔。
        registry.add("spring.autoconfigure.exclude", () -> EXCLUDED_AUTOCONFIGURATIONS);
        // 推荐索引不在启动时构建，避免测试启动阶段访问向量库或消耗额外时间。
        // 关闭启动索引后，推荐相关测试自行控制索引构建时机。
        registry.add("toolshare.ai.recommend.index-on-startup", () -> false);
    }
}
