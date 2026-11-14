package com.cdwater.toolshare.module.message;

/**
 * 短信发送适配器。当前只有 Mock 实现，不接真实网关。
 */
public interface SmsSender {

    /**
     * 发送短信；失败时抛出异常，由调用方记录 FAILED 送达状态。
     */
    void send(String phone, String content);
}
