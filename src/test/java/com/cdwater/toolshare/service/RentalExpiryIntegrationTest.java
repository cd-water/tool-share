package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportMapper;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.rental.RentalService;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogMapper;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 待支付超时：超时时间为 0 分钟，验证过期任务取消租赁单后档期可再次预约。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolshare.rental.pending-payment-timeout-minutes=0")
@Import(TestStorageConfig.class)
class RentalExpiryIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String PREFIX = "IT-EXPIRE-";

    @LocalServerPort
    int port;

    @Autowired
    ToolMapper toolMapper;
    @Autowired
    ToolLogMapper toolLogMapper;
    @Autowired
    RentalMapper rentalMapper;
    @Autowired
    DamageReportMapper damageReportMapper;
    @Autowired
    RentalService rentalService;

    RestClient client;
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void cleanUp() {
        List<Tool> tools = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .likeRight(Tool::getName, PREFIX));
        for (Tool tool : tools) {
            damageReportMapper.delete(new LambdaQueryWrapper<DamageReport>()
                    .eq(DamageReport::getToolId, tool.getId()));
            rentalMapper.delete(new LambdaQueryWrapper<Rental>()
                    .eq(Rental::getToolId, tool.getId()));
            toolLogMapper.delete(new LambdaQueryWrapper<ToolLog>()
                    .eq(ToolLog::getToolId, tool.getId()));
            toolMapper.deleteById(tool.getId());
        }
    }

    @Test
    void expired_pending_payment_releases_the_slot() {
        String admin = adminLogin();
        String user = userLogin();
        long toolId = createApprovedTool(admin, "expire");
        Map<String, Object> booking = booking(toolId);

        long rentalId = call(HttpMethod.POST, "/api/user/rentals", user, booking)
                .get("data").get("id").asLong();

        rentalService.expirePending();

        Rental expired = rentalMapper.selectById(rentalId);
        assertEquals("CANCELLED", expired.getStatus());
        assertEquals("UNPAID", expired.getDepositStatus());

        JsonNode rebooked = call(HttpMethod.POST, "/api/user/rentals", user, booking);
        assertEquals("A200", rebooked.get("code").asString(), rebooked.toString());
    }

    private Map<String, Object> booking(long toolId) {
        return Map.of(
                "toolId", toolId,
                "startTime", LocalDateTime.now().plusDays(2).withNano(0).format(FORMAT),
                "endTime", LocalDateTime.now().plusDays(3).withNano(0).format(FORMAT),
                "pickupPerson", "王建国");
    }

    private long createApprovedTool(String admin, String suffix) {
        JsonNode created = call(HttpMethod.POST, "/api/admin/tools", admin, Map.of(
                "name", PREFIX + suffix,
                "model", "测试型号",
                "category", "WX",
                "damageLevel", "NEW",
                "deposit", 100,
                "storageArea", "测试柜",
                "usageGuide", "<p>说明</p>",
                "imageUrls", List.of()));
        long id = created.get("data").get("id").asLong();
        JsonNode audit = call(HttpMethod.PATCH, "/api/admin/tools/" + id + "/audit", admin,
                Map.of("auditStatus", "APPROVED"));
        assertEquals("A200", audit.get("code").asString(), audit.toString());
        return id;
    }

    private String adminLogin() {
        return login("/api/admin/login", Map.of("username", "admin", "password", "Aa123456"));
    }

    private String userLogin() {
        return login("/api/user/login/by-pwd", Map.of("phone", "13800000001", "password", "Aa123456"));
    }

    private String login(String url, Map<String, Object> body) {
        JsonNode response = call(HttpMethod.POST, url, null, body);
        assertEquals("A200", response.get("code").asString(), response.toString());
        return response.get("data").get("tokenValue").asString();
    }

    private JsonNode call(HttpMethod method, String url, String token, Object body) {
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
                return om.readTree(raw.isBlank() ? "{}" : raw);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
