package com.cdwater.toolshare.module.message;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 消息对外视图对象。
 * <p>相比实体额外提供消息类型中文名，并把 0/1 已读标记转换为布尔值，
 * <p>字段说明：
 * <ul>
 *   <li>type/typeName：消息业务类型编码与中文名；</li>
 *   <li>channel：最终实际投递渠道，可能已从 SMS 回退为 SYSTEM；</li>
 *   <li>deliveryStatus：投递状态，与是否已读相互独立；</li>
 *   <li>sentTime：实际发送时间，短信失败时为空；</li>
 *   <li>readStatus：布尔已读状态，由数据库 0/1 转换而来。</li>
 *   <li>channel 与 deliveryStatus 只描述投递，不表示居民是否已读；</li>
 *   <li>sentTime 为空通常意味着短信发送失败，但消息仍可在站内查看。</li>
 * </ul>
 * 由 {@link MessageVoMapper} 在映射后补齐。
 */
public class MessageVO {

    /** 消息主键。 */
    private Long id;
    @Schema(allowableValues = {
            "RESERVATION", "PICKUP", "RENT_DUE", "OVERDUE", "REPAIR", "TOOL_AVAILABLE",
            "ANNOUNCEMENT", "SYSTEM"})
    /** 消息类型编码。 */
    private String type;
    /** 消息类型中文名称。 */
    private String typeName;
    /** 消息标题。 */
    private String title;
    /** 消息正文。 */
    private String content;
    @Schema(allowableValues = {"SYSTEM", "SMS"})
    /** 实际投递渠道。 */
    private String channel;
    @Schema(allowableValues = {"PENDING", "SENT", "DELIVERED", "FAILED"})
    /** 投递状态编码。 */
    private String deliveryStatus;
    /** 发送时间，失败时为空。 */
    private LocalDateTime sentTime;
    /** 是否已读，由 0/1 转换而来。 */
    private Boolean readStatus;
    /** 创建时间。 */
    private LocalDateTime createTime;
}
