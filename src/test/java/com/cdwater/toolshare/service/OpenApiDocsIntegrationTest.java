package com.cdwater.toolshare.service;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI 契约可访问且覆盖现有全部端点；/v3/api-docs 不被 sa-token 拦截。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class OpenApiDocsIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> EXPECTED_PATHS = List.of(
            "/api/user/profile",
            "/api/user/upload",
            "/api/user/send-code",
            "/api/user/login/by-pwd",
            "/api/user/login/by-code",
            "/api/user/logout",
            "/api/user/info",
            "/api/user/dashboard",
            "/api/user/tools",
            "/api/user/tools/{id}",
            "/api/user/tools/{id}/availability",
            "/api/user/tools/{id}/watch",
            "/api/user/tools/watch/list",
            "/api/user/rentals",
            "/api/user/rentals/{id}",
            "/api/user/rentals/{id}/pay",
            "/api/user/rentals/{id}/cancel",
            "/api/user/damage-reports",
            "/api/user/damage-reports/{id}",
            "/api/user/messages",
            "/api/user/messages/unread-count",
            "/api/user/messages/{id}/read",
            "/api/user/messages/read-all",
            "/api/user/notification-prefs",
            "/api/user/notification-prefs/{type}",
            "/api/user/ai/chat",
            "/api/user/ai/chat/stream",
            "/api/user/ai/tool-recommend",
            "/api/user/ai/tool-recommend/{id}/click",
            "/api/user/ai/tool-recommend/{id}/feedback",
            "/api/admin/login",
            "/api/admin/logout",
            "/api/admin/info",
            "/api/admin/dashboard",
            "/api/admin/upload",
            "/api/admin/tools",
            "/api/admin/tools/{id}",
            "/api/admin/tools/{id}/audit",
            "/api/admin/tools/{id}/status",
            "/api/admin/tools/{id}/logs",
            "/api/admin/rentals",
            "/api/admin/rentals/{id}",
            "/api/admin/rentals/{id}/pickup",
            "/api/admin/rentals/{id}/return",
            "/api/admin/rentals/{id}/cancel",
            "/api/admin/rentals/export",
            "/api/admin/damage-reports",
            "/api/admin/damage-reports/{id}",
            "/api/admin/damage-reports/{id}/verify",
            "/api/admin/damage-reports/{id}/progress",
            "/api/admin/damage-reports/{id}/accept",
            "/api/admin/messages",
            "/api/admin/messages/unread-count",
            "/api/admin/messages/{id}/read",
            "/api/admin/messages/read-all",
            "/api/admin/knowledge",
            "/api/admin/knowledge/{id}",
            "/api/files/**");

    @LocalServerPort
    int port;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void api_docs_accessible_without_auth_and_covers_all_endpoints() {
        JsonNode doc = client.get().uri("/v3/api-docs")
                .exchange((request, response) -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    try (InputStream in = response.getBody()) {
                        return om.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                });
        JsonNode paths = doc.get("paths");
        assertNotNull(paths, "OpenAPI 文档缺少 paths");
        for (String path : EXPECTED_PATHS) {
            assertTrue(paths.has(path), "OpenAPI 文档缺少端点: " + path);
        }
    }
}
