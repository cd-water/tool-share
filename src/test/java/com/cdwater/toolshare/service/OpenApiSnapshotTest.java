package com.cdwater.toolshare.service;

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

/**
 * 重新生成前端契约快照：仅在显式开启时写入 docs/api/openapi.json。
 *
 * <p>生成命令：{@code ./mvnw test -Dtest=OpenApiSnapshotTest -Dtoolshare.openapi.snapshot=true}。
 * 默认 {@code ./mvnw test} 不会改写快照文件。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestStorageConfig.class)
class OpenApiSnapshotTest extends AbstractIntegrationTest {

    private static final Path SNAPSHOT = Path.of("docs", "api", "openapi.json");

    @Autowired
    MockMvc mockMvc;

    @Test
    @EnabledIfSystemProperty(named = "toolshare.openapi.snapshot", matches = "true")
    void write_openapi_snapshot() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        Files.createDirectories(SNAPSHOT.getParent());
        Files.writeString(SNAPSHOT, json + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
