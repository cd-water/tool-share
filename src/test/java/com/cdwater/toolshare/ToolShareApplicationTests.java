package com.cdwater.toolshare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestStorageConfig.class)
class ToolShareApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}
