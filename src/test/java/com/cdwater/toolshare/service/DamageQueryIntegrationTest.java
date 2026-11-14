package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.tool.ToolMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报修检索：#16 居民按单号/工具编号检索、状态筛选、分页与归属校验；管理端组合筛选。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class DamageQueryIntegrationTest extends AbstractIntegrationTest {

    private static final String PREFIX = "IT-DQRY-";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    int port;

    @Autowired
    DamageReportMapper damageReportMapper;
    @Autowired
    ToolMapper toolMapper;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    record ApiResponse(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void cleanUp() {
        damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                .likeRight(DamageReport::getReportNo, PREFIX));
    }

    @Test
    void user_searches_own_reports_by_report_no_or_tool_code() {
        String user = userLogin();
        long byNo = insertReport(1L, 1L, "IT-DQRY-CODE-NO", "冲击钻", "王建国",
                "SUBMITTED", "no-match");
        long byCode = insertReport(1L, 2L, "IT-DQRY-CODE-CODE", "工具箱", "王建国",
                "SUBMITTED", "code-match");
        insertReport(1L, 1L, "IT-DQRY-CODE-OTHER", "切割机", "王建国", "SUBMITTED", "other");

        List<Long> noResult = ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=" + reportNo(byNo) + "&size=100", user)));
        assertEquals(List.of(byNo), noResult);

        List<Long> codeResult = ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-CODE-CODE&size=100", user)));
        assertEquals(List.of(byCode), codeResult);

        // 居民关键字仅覆盖报修单号与工具编号，不扩大到工具名
        assertTrue(ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=切割机&size=100", user))).isEmpty());
    }

    @Test
    void user_filters_by_status_and_paginates_server_side() {
        String user = userLogin();
        long first = insertReport(1L, 1L, "IT-DQRY-PAGE-A", "电钻", "王建国",
                "SUBMITTED", "page-a");
        long second = insertReport(1L, 2L, "IT-DQRY-PAGE-B", "电钻", "王建国",
                "SUBMITTED", "page-b");
        long completed = insertReport(1L, 1L, "IT-DQRY-PAGE-C", "电钻", "王建国",
                "COMPLETED", "page-c");

        List<Long> submitted = ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-PAGE&status=SUBMITTED&size=100", user)));
        assertTrue(submitted.containsAll(List.of(first, second)), submitted.toString());
        assertFalse(submitted.contains(completed), submitted.toString());

        JsonNode pageOne = ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-PAGE&page=1&size=2", user));
        assertEquals(2, pageOne.get("data").get("records").size());
        assertEquals(2, pageOne.get("data").get("size").asLong());
        assertEquals(3, pageOne.get("data").get("total").asLong());
        JsonNode pageTwo = ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-PAGE&page=2&size=2", user));
        assertEquals(1, pageTwo.get("data").get("records").size());

        JsonNode clamped = ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=IT-DQRY-PAGE&size=200", user));
        assertEquals(100, clamped.get("data").get("size").asLong());
    }

    @Test
    void user_cannot_see_other_residents_reports() {
        String user = userLogin();
        long own = insertReport(1L, 1L, "IT-DQRY-CODE-OWN", "手电钻", "王建国",
                "SUBMITTED", "own");
        long others = insertReport(2L, 2L, "IT-DQRY-CODE-OTHERS", "手电钻", "李四",
                "SUBMITTED", "others");

        List<Long> list = ids(ok(call(HttpMethod.GET,
                "/api/user/damage-reports?size=100", user)));
        assertTrue(list.contains(own), list.toString());
        assertFalse(list.contains(others), list.toString());

        ApiResponse forbidden = call(HttpMethod.GET, "/api/user/damage-reports/" + others, user);
        assertEquals(403, forbidden.status(), forbidden.body().toString());
        assertEquals("C403", forbidden.body().get("code").asString());

        ApiResponse missing = call(HttpMethod.GET, "/api/user/damage-reports/999999999", user);
        assertEquals(404, missing.status(), missing.body().toString());
        assertEquals("C404", missing.body().get("code").asString());
    }

    @Test
    void admin_combines_keyword_and_status_filters() {
        String admin = adminLogin();
        long alpha = insertReport(1L, 1L, "IT-DQRY-ADM-A", "IT-DQRY-冲击钻-alpha", "甲",
                "SUBMITTED", "admin-a");
        long beta = insertReport(2L, 2L, "IT-DQRY-ADM-B", "IT-DQRY-切割机-beta", "乙",
                "COMPLETED", "admin-b");

        List<Long> byToolName = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=alpha&size=100", admin)));
        assertTrue(byToolName.contains(alpha), byToolName.toString());
        assertFalse(byToolName.contains(beta), byToolName.toString());

        List<Long> byToolCode = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=IT-DQRY-ADM-B&size=100", admin)));
        assertTrue(byToolCode.contains(beta), byToolCode.toString());

        List<Long> byReporter = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=乙&size=100", admin)));
        assertTrue(byReporter.contains(beta), byReporter.toString());

        List<Long> byReportNo = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=" + reportNo(alpha) + "&size=100", admin)));
        assertTrue(byReportNo.contains(alpha), byReportNo.toString());

        List<Long> combined = ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=IT-DQRY-&status=COMPLETED&size=100", admin)));
        assertTrue(combined.contains(beta), combined.toString());
        assertFalse(combined.contains(alpha), combined.toString());

        assertTrue(ids(ok(call(HttpMethod.GET,
                "/api/admin/damage-reports?keyword=alpha&status=COMPLETED&size=100", admin)))
                .isEmpty());
    }

    @Test
    void list_includes_tool_main_image() {
        String user = userLogin();
        long report = insertReport(1L, 1L, "IT-DQRY-CODE-IMG", "手电钻", "王建国",
                "SUBMITTED", "img");

        JsonNode record = ok(call(HttpMethod.GET,
                "/api/user/damage-reports?keyword=" + reportNo(report) + "&size=100", user))
                .get("data").get("records").get(0);
        assertNotNull(record.get("toolImageUrl"), toolMapper.selectById(1L) + " " + record);
        assertEquals(toolMapper.selectById(1L).getImageUrl(),
                record.get("toolImageUrl").asString(), record.toString());
        assertFalse(record.get("toolImageUrl").asString().isBlank(), record.toString());
    }

    private long insertReport(Long userId, Long toolId, String toolCode, String toolName,
                              String reporterName, String status, String suffix) {
        DamageReport report = new DamageReport();
        report.setReportNo(PREFIX + SEQ.incrementAndGet() + "-" + suffix);
        report.setRentalId(900000L + SEQ.get());
        report.setToolId(toolId);
        report.setToolCode(toolCode);
        report.setToolName(toolName);
        report.setUserId(userId);
        report.setReporterName(reporterName);
        report.setReporterPhone("13900000001");
        report.setDamagePart("电源接口");
        report.setDescription("通电后无反应");
        report.setImages("");
        report.setStatus(status);
        damageReportMapper.insert(report);
        return report.getId();
    }

    private String reportNo(long id) {
        return damageReportMapper.selectById(id).getReportNo();
    }

    private List<Long> ids(JsonNode body) {
        List<Long> result = new ArrayList<>();
        for (JsonNode record : body.get("data").get("records")) {
            result.add(record.get("id").asLong());
        }
        return result;
    }

    private JsonNode ok(ApiResponse response) {
        assertEquals(200, response.status(), response.body().toString());
        assertEquals("A200", response.body().get("code").asString(), response.body().toString());
        return response.body();
    }

    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    private String login(String url, Map<String, Object> body) {
        ApiResponse response = call(HttpMethod.POST, url, null, body);
        assertEquals(200, response.status(), response.body().toString());
        return response.body().get("data").get("tokenValue").asString();
    }

    private ApiResponse call(HttpMethod method, String url, String token) {
        return call(method, url, token, null);
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
