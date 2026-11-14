package com.cdwater.toolshare.module.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock 短信适配器：只打日志，不接真实网关。
 */
@Slf4j
@Component
/**
 * <p>实现只把手机号与内容写入 INFO 日志，便于本地开发和联调时观察短信
 * 触发时机；生产环境接入真实网关时应替换为新的 {@link SmsSender} 实现，
 * 本类保持无状态、无外部依赖。
 */
public class MockSmsSender implements SmsSender {

    /**
     * 模拟发送短信。
     * <p>不访问网络、不抛异常，确保调用方的投递状态记录流程可被完整验证。
     *
     * @param phone   接收手机号
     * @param content 短信正文
     */
    @Override
    public void send(String phone, String content) {
        log.info("[MOCK SMS] phone={}, content={}", phone, content);
    }
}
