package com.cdwater.toolshare.rental;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租赁预约时段冲突校验集成测试（依赖本地 MySQL/Redis/RabbitMQ）
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RentalServiceTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void create_rejectsOverlappingReservedSlot() throws Exception {
        String loginBody = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"zhangsan","password":"Aa123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A200"))
                .andReturn().getResponse().getContentAsString();
        Matcher tokenMatcher = TOKEN.matcher(loginBody);
        assertNotNull(tokenMatcher.find() ? tokenMatcher.group(1) : null, "登录应返回 token");
        String token = tokenMatcher.group(1);

        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        LocalDateTime start = LocalDateTime.now().plusDays(30).withHour(9).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(3);

        String createBody = """
                {"toolId":4,"startTime":"%s","endTime":"%s","pickupName":"张三（本人）"}
                """.formatted(start.format(iso), end.format(iso));

        String first = mockMvc.perform(post("/api/rental")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A200"))
                .andReturn().getResponse().getContentAsString();

        Matcher idMatcher = ID.matcher(first);
        assertNotNull(idMatcher.find() ? idMatcher.group(1) : null, "创建预约应返回 id");
        long id = Long.parseLong(idMatcher.group(1));

        // 缴纳押金后预约生效（status=已预约），才会参与冲突校验
        mockMvc.perform(post("/api/rental/" + id + "/pay").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A200"));

        // 同一工具、重叠时段的第二次预约应被拒绝
        String conflictBody = """
                {"toolId":4,"startTime":"%s","endTime":"%s","pickupName":"张三（本人）"}
                """.formatted(start.plusHours(1).format(iso), end.plusHours(1).format(iso));
        mockMvc.perform(post("/api/rental")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("C409"));
    }

    @Test
    void create_allowsNonOverlappingSlot() throws Exception {
        String loginBody = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"zhangsan","password":"Aa123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher tokenMatcher = TOKEN.matcher(loginBody);
        assertNotNull(tokenMatcher.find() ? tokenMatcher.group(1) : null);
        String token = tokenMatcher.group(1);

        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        LocalDateTime start = LocalDateTime.now().plusDays(31).withHour(9).withMinute(0).withSecond(0).withNano(0);

        String body = """
                {"toolId":4,"startTime":"%s","endTime":"%s","pickupName":"张三（本人）"}
                """.formatted(start.format(iso), start.plusHours(1).format(iso));

        mockMvc.perform(post("/api/rental")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A200"));
    }
}
