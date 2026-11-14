package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 集成测试：管理端三级权限（居民 / 工作人员 / 系统管理员）。
 * 种子管理员 admin 迁移后为 SUPER_ADMIN；测试内创建 STAFF 账号验证允许与拒绝路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class AdminPermissionIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Aa123456";
    private static final String STAFF_USERNAME = "it_staff";
    /** 与 V2 seed 相同，明文为 Aa123456 */
    private static final String PASSWORD_HASH =
            "$2a$10$GIo5IeU8h/hoUoXFt6sFOu91qMy.0QrjdS6zO0jcC9znCQ.5cIvpC";

    @LocalServerPort
    int port;

    @Autowired
    AdminMapper adminMapper;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    record ApiResponse(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        deleteStaff();
        Admin staff = new Admin();
        staff.setUsername(STAFF_USERNAME);
        staff.setPassword(PASSWORD_HASH);
        staff.setStatus(1);
        staff.setRole("STAFF");
        adminMapper.insert(staff);
    }

    @AfterEach
    void tearDown() {
        deleteStaff();
    }

    private void deleteStaff() {
        adminMapper.delete(new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, STAFF_USERNAME));
    }

    @Test
    void seed_admin_migrated_to_super_admin_and_can_login() {
        ApiResponse login = call(HttpMethod.POST, "/api/admin/login", null,
                Map.of("username", "admin", "password", PASSWORD));
        assertEquals(200, login.status(), login.body().toString());
        String token = login.body().get("data").get("tokenValue").asString();

        ApiResponse info = call(HttpMethod.GET, "/api/admin/info", token, null);
        assertEquals(200, info.status(), info.body().toString());
        assertEquals("admin", info.body().get("data").get("username").asString());
        assertEquals("SUPER_ADMIN", info.body().get("data").get("role").asString());
    }

    @Test
    void super_admin_can_access_every_admin_area() {
        String token = login("admin", PASSWORD);

        for (String path : List.of(
                "/api/admin/tools", "/api/admin/knowledge", "/api/admin/admins",
                "/api/admin/rentals", "/api/admin/damage-reports",
                "/api/admin/messages", "/api/admin/dashboard")) {
            ApiResponse resp = call(HttpMethod.GET, path, token, null);
            assertEquals(200, resp.status(), path + " -> " + resp.body());
            assertEquals("A200", resp.body().get("code").asString(), path);
        }
    }

    @Test
    void staff_can_access_operational_areas_and_upload() throws IOException {
        String token = login(STAFF_USERNAME, PASSWORD);
        for (String path : List.of(
                "/api/admin/rentals", "/api/admin/damage-reports",
                "/api/admin/messages", "/api/admin/dashboard")) {
            ApiResponse resp = call(HttpMethod.GET, path, token, null);
            assertEquals(200, resp.status(), path + " -> " + resp.body());
            assertEquals("A200", resp.body().get("code").asString(), path);
        }
        ApiResponse uploaded = upload(token);
        assertEquals(200, uploaded.status(), uploaded.body().toString());
    }

    @Test
    void staff_forbidden_from_tool_knowledge_and_admin_management() {
        String token = login(STAFF_USERNAME, PASSWORD);
        for (String path : List.of(
                "/api/admin/tools", "/api/admin/knowledge", "/api/admin/admins")) {
            ApiResponse resp = call(HttpMethod.GET, path, token, null);
            assertEquals(403, resp.status(), path + " -> " + resp.body());
            assertEquals("C403", resp.body().get("code").asString(), path);
        }
    }

    @Test
    void super_admin_can_update_admin_role_and_permissions_take_effect() {
        String token = login("admin", PASSWORD);
        Admin staff = adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, STAFF_USERNAME));

        ApiResponse updated = call(HttpMethod.PATCH,
                "/api/admin/admins/" + staff.getId() + "/role", token, Map.of("role", "SUPER_ADMIN"));
        assertEquals(200, updated.status(), updated.body().toString());

        String staffToken = login(STAFF_USERNAME, PASSWORD);
        assertEquals(200, call(HttpMethod.GET, "/api/admin/tools", staffToken, null).status());
    }

    @Test
    void super_admin_cannot_set_unknown_role() {
        String token = login("admin", PASSWORD);
        Admin staff = adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, STAFF_USERNAME));

        ApiResponse resp = call(HttpMethod.PATCH,
                "/api/admin/admins/" + staff.getId() + "/role", token, Map.of("role", "OWNER"));
        assertEquals(400, resp.status(), resp.body().toString());
        assertEquals("C400", resp.body().get("code").asString());
    }

    @Test
    void staff_info_returns_staff_role() {
        String token = login(STAFF_USERNAME, PASSWORD);
        ApiResponse info = call(HttpMethod.GET, "/api/admin/info", token, null);
        assertEquals(200, info.status(), info.body().toString());
        assertEquals("STAFF", info.body().get("data").get("role").asString());
    }

    private ApiResponse upload(String token) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(png.toByteArray()))
                .filename("proof.png")
                .contentType(MediaType.IMAGE_PNG);
        return client.post().uri("/api/admin/upload")
                .header("Authorization", "Bearer " + token)
                .body(body.build())
                .exchange((request, response) -> {
                    try (InputStream in = response.getBody()) {
                        String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        return new ApiResponse(response.getStatusCode().value(),
                                om.readTree(raw.isBlank() ? "{}" : raw));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    private String login(String username, String password) {
        ApiResponse resp = call(HttpMethod.POST, "/api/admin/login", null,
                Map.of("username", username, "password", password));
        assertEquals(200, resp.status(), resp.body().toString());
        return resp.body().get("data").get("tokenValue").asString();
    }

    private ApiResponse call(HttpMethod method, String url, String token, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(url);
        if (token != null) {
            spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            try (InputStream in = response.getBody()) {
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new ApiResponse(response.getStatusCode().value(),
                        om.readTree(raw.isBlank() ? "{}" : raw));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
