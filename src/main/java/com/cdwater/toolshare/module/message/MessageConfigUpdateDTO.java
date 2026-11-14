package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 更新消息渠道配置的请求体。
 *
 * @param defaultChannel 默认投递渠道，只允许 SYSTEM 或 SMS
 * <p>两个字段都是整体覆盖语义：未提交的字段由校验注解拦下，
 * 不会出现部分更新导致状态不确定。
 * @param smsEnabled     是否启用短信通道
 */
public record MessageConfigUpdateDTO(
        @NotBlank @Schema(allowableValues = {"SYSTEM", "SMS"}) String defaultChannel,
        // 默认渠道同时影响居民未单独配置偏好时的投递方式。
        @NotNull Boolean smsEnabled) {
        // 关闭短信后，选择 SMS 的居民会回退到站内信，而不是收不到提醒。
}
