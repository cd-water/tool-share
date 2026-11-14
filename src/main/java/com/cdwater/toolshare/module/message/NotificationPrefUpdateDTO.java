package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 更新单个消息类型提醒偏好的请求体。
 *
 * @param enabled 是否接收该类型提醒
 * <p>关闭提醒时渠道仍会被保存，方便下次开启时恢复。
 * @param channel 投递渠道，只允许 SYSTEM 或 SMS
 */
public record NotificationPrefUpdateDTO(
        @NotNull Boolean enabled,
        // 关闭后该类型提醒不再写入消息表，也不触发短信。
        @NotBlank @Schema(allowableValues = {"SYSTEM", "SMS"}) String channel) {
        // 渠道只在开启时生效；关闭状态仍被保存，便于再次开启时恢复选择。
}
