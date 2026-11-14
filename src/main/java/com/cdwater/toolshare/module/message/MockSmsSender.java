package com.cdwater.toolshare.module.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock 短信适配器：只打日志，不接真实网关。
 */
@Slf4j
@Component
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phone, String content) {
        log.info("[MOCK SMS] phone={}, content={}", phone, content);
    }
}
