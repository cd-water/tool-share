package com.cdwater.toolshare.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单号生成器集成测试（依赖本地 Redis/MySQL 等中间件）
 */
@SpringBootTest
class SerialNoGeneratorTest {

    @Autowired
    private SerialNoGenerator generator;

    @Test
    void next_matchesFormatAndIncrements() {
        String a = generator.next("SQ-TEST-");
        String b = generator.next("SQ-TEST-");
        assertTrue(a.matches("SQ-TEST-\\d{8}\\d{4}"), "格式应为 前缀+日期+序号: " + a);
        assertEquals(Integer.parseInt(a.substring(a.length() - 4)) + 1,
                Integer.parseInt(b.substring(b.length() - 4)), "同前缀日内序号应递增");
    }
}
