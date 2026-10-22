package com.cdwater.toolshare.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单号生成器集成测试（依赖本地 Redis/MySQL 等中间件）
 */
@SpringBootTest
class SerialNoGeneratorTest {

    // S3Client Bean 由后端 A 提供，本分支合并前用 mock 保证上下文可加载
    @MockitoBean
    private S3Client s3Client;

    @Autowired
    private SerialNoGenerator generator;

    @Test
    void next_matchesFormatAndIncrements() {
        String a = generator.next("SQ-TEST-");
        String b = generator.next("SQ-TEST-");
        assertTrue(a.matches("SQ-TEST-\\d{8}-\\d{4}"), "格式应为 前缀+日期+序号: " + a);
        assertEquals(Integer.parseInt(a.substring(a.length() - 4)) + 1,
                Integer.parseInt(b.substring(b.length() - 4)), "同前缀日内序号应递增");
    }
}
