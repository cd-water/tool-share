package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 消息渠道配置的响应体。
 *
 * @param defaultChannel 当前默认投递渠道
 * <p>这是管理端配置页的完整视图，不包含数据库主键等内部字段。
 * @param smsEnabled     短信通道是否启用
 */
public record MessageConfigVO(
        @Schema(allowableValues = {"SYSTEM", "SMS"}) String defaultChannel,
        // 管理端配置页直接使用该值回显下拉选项。
        Boolean smsEnabled) {
        // 短信开关关闭时，实际发送会自动回退到站内信。
}
