package com.cdwater.toolshare.service;

// 本测试只依赖 MockMvc 与标准文件 API：
// MockMvc 直接调用 Spring MVC 层，不启动真实 HTTP 端口，速度快且结果稳定；
// Files/Path 用于把生成的文档写入仓库内的契约快照文件。
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 该测试不是常规回归测试，而是显式的“快照生成器”。
// 默认测试运行通过 @EnabledIfSystemProperty 关闭，避免开发者无意间改写被版本控制的文档。
// 需要更新契约时，先由人工确认接口变更，再以系统属性显式开启执行。
// 生成结果写入 docs/api/openapi.json，供前端类型生成与契约 diff 使用。
// 写入内容与服务器 /v3/api-docs 的返回完全一致，因此可以作为发布契约的事实来源。
/**
 * 重新生成前端契约快照：仅在显式开启时写入 docs/api/openapi.json。
 *
 * <p>生成命令：{@code ./mvnw test -Dtest=OpenApiSnapshotTest -Dtoolshare.openapi.snapshot=true}。
 * 默认 {@code ./mvnw test} 不会改写快照文件。</p>
 */
// 使用完整 Spring 上下文而非切片测试，因为文档由全部控制器与安全配置共同生成。
// MockMvc 模式不占用真实端口，适合这种只读取文档、不需要网络交互的场景。
// 测试存储使用内存实现，避免快照生成依赖 MinIO 等外部服务。
// 文档端点的访问控制在安全配置中放行，因此无需构造登录令牌。
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestStorageConfig.class)
class OpenApiSnapshotTest extends AbstractIntegrationTest {

    // 快照文件相对仓库根目录定位，Maven 从项目根目录执行测试。
    // 使用 Path.of 而不是字符串拼接，保证跨平台路径分隔符正确。
    private static final Path SNAPSHOT = Path.of("docs", "api", "openapi.json");

    // MockMvc 由 @AutoConfigureMockMvc 注入，复用完整的 MVC 配置与安全过滤链。
    @Autowired
    MockMvc mockMvc;

    // 快照生成属于人工触发的维护操作，而不是每次构建都执行的断言。
    // 这样既避免误覆盖契约文件，也让 CI 的常规测试保持只读。
    // 更新快照后应把 docs/api/openapi.json 与接口改动一起提交评审。
    // 该方法的执行条件完全由系统属性控制：
    // 属性缺失或不为 true 时，JUnit 直接跳过，不产生任何文件副作用；
    // 属性匹配时才会访问文档端点并覆盖快照。
    // 因此默认 CI 的 ./mvnw test 永远不会修改契约文件。
    /**
     * 场景：在显式开启快照属性时，调用 /v3/api-docs 并写回契约文件。
     * 前置条件：应用上下文已启动，数据库/缓存容器由基座提供；
     * 系统属性 toolshare.openapi.snapshot=true 才允许执行。
     * 断言目标：文档端点返回 200，且响应体被原样写入 docs/api/openapi.json。
     * 副作用：会覆盖受版本控制的快照文件，必须与接口变更一起提交评审。
     */
    @Test
    @EnabledIfSystemProperty(named = "toolshare.openapi.snapshot", matches = "true")
    void write_openapi_snapshot() throws Exception {
        // 通过 MockMvc 请求文档端点；该路径在安全配置中放行，不需要登录令牌。
        // 先断言状态码为 200，再读取 UTF-8 响应体，保持与浏览器访问一致的编码。
        // 响应体已经是格式化后的 JSON 字符串，直接落盘即可。
        // 不在此处做排序或裁剪，避免生成结果与运行时文档不一致。
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // 取出 MockHttpServletResponse 的正文；使用 UTF-8 显式解码，避免平台默认编码差异。
                .andReturn()
                // 对响应体只做读取，不在此处解析或格式化，确保快照字节级稳定。
                .getResponse()
                // 换行符统一由写入阶段追加，避免正文末尾多出重复空行。
                .getContentAsString(StandardCharsets.UTF_8);

        // 确保 docs/api 目录存在；首次生成或清理后目录可能缺失。
        // createDirectories 对已存在目录是幂等操作，不会报错。
        // 目录创建与文件写入都使用仓库相对路径，
        // 因此必须从项目根目录执行 Maven，快照位置才符合前端约定。
        Files.createDirectories(SNAPSHOT.getParent());
        // 写入格式化的 JSON 并显式追加系统换行符，便于 Git 逐行比较。
        // 写入使用 UTF-8，与前端读取约定保持一致。
        Files.writeString(SNAPSHOT, json + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
