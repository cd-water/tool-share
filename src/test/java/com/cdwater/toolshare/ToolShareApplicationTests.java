package com.cdwater.toolshare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 应用启动冒烟测试。
 * <p>
 * 测试范围：确认 Spring Boot 上下文能够在测试配置下完整加载。
 * 依赖环境：完整应用配置、测试用对象存储配置 {@link TestStorageConfig}。
 * 关键不变量：所有 Bean 装配成功、数据源与缓存初始化完成，不依赖任何业务数据。
 */
// 启动完整的 Spring Boot 上下文，覆盖 Bean 装配、数据源与缓存初始化。
// 冒烟测试不涉及业务数据，只关心应用能否正常启动。
@SpringBootTest
@Import(TestStorageConfig.class)
// 继承集成测试基类以获得测试库与容器生命周期管理；
// 同时导入内存对象存储配置，避免依赖外部 MinIO。
class ToolShareApplicationTests extends AbstractIntegrationTest {

    /**
     * 场景：加载完整应用上下文后不做任何业务调用。
     * 前置条件：测试类上的 @SpringBootTest 与对象存储测试配置生效。
     * 断言目标：上下文启动本身不抛异常，证明依赖注入与外部配置可用。
     */
    @Test
    // 方法体为空是刻意的：上下文能加载完成即代表断言成功。
    // 若任何 Bean 或配置缺失，测试会在启动阶段失败。
    void contextLoads() {
    }

}
