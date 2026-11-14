package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
/**
 * 居民提醒偏好视图对象，用于设置页展示。
 * <p>包含消息类型编码与中文名、当前渠道以及是否开启；未保存过偏好的
 * <p>enabled 与 channel 是居民可修改的两项；type/typeName 仅用于展示，
 * <p>type 使用编码而不是枚举序号，保证前后端协议稳定。
 * <p>列表与更新接口返回同一结构，前端可用同一套渲染逻辑。
 * 修改接口通过路径参数指定类型，不在请求体中重复传递。
 * 类型也会返回默认值，前端因此可以直接渲染完整设置列表。
 */
public class NotificationPrefVO {

    @Schema(allowableValues = {
            "RESERVATION", "PICKUP", "RENT_DUE", "OVERDUE", "REPAIR", "TOOL_AVAILABLE",
            "ANNOUNCEMENT", "SYSTEM"})
    /** 消息类型编码。 */
    private String type;

    /** 消息类型中文名称。 */
    private String typeName;

    /** SYSTEM/SMS */
    @Schema(allowableValues = {"SYSTEM", "SMS"})
    /** 当前投递渠道：SYSTEM 或 SMS。 */
    private String channel;

    /** 是否接收该类型提醒。 */
    private Boolean enabled;
}
