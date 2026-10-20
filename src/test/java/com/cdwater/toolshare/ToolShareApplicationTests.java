package com.cdwater.toolshare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest
class ToolShareApplicationTests {

    // S3Client Bean 由后端 A 提供，本分支合并前用 mock 保证上下文可加载
    @MockitoBean
    private S3Client s3Client;

    @Test
    void contextLoads() {
    }

}
