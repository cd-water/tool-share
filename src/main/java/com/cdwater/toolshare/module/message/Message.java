package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message")
/**
 * 站内消息持久化实体，对应表 {@code t_message}。
 * <p>一条记录同时承载站内信与短信两种渠道的投递结果：channel 表示最终
 * 实际使用的渠道，delivery_status 表示投递状态，read_status 表示居民
 * 是否已读。消息正文与标题在发送时确定，后续不再修改。
 * <p>biz_key 由数据库唯一索引约束，用于业务事件级幂等；居民与管理员的
 * <p>标题与正文在发送时确定，后续不再修改。
 * 消息共用本表，通过 receiver_type + receiver_id 区分归属。
 */
public class Message {
    // 本实体同时承载站内信与短信的投递结果。
    // channel 是最终实际渠道，可能已从 SMS 回退。
    // delivery_status 与 read_status 相互独立。
    // biz_key 由唯一索引约束，用于业务幂等。

    @TableId(type = IdType.AUTO)
    /** 消息主键，数据库自增。 */
    private Long id;

    /** 接收人类型：USER（居民）或 ADMIN（管理员）。 */
    private String receiverType;

    /** 接收人主键：居民为 user_id，管理员为 admin_id。 */
    private Long receiverId;

    /** 消息业务类型编码，对应 MessageType 枚举。 */
    private String type;

    /** 消息标题，列表展示与短信前缀。 */
    private String title;

    /** 消息正文，站内信与短信共用。 */
    private String content;

    /** 最终实际投递渠道：SYSTEM（站内信）或 SMS（短信）。 */
    private String channel;

    /** 投递状态：PENDING/SENT/DELIVERED/FAILED。 */
    private String deliveryStatus;

    /** 实际发送时间；短信失败时为空。 */
    private LocalDateTime sentTime;

    /** 已读标记：0 未读，1 已读。 */
    private Integer readStatus;

    /** 业务幂等键，唯一索引；为空表示不做去重。 */
    private String bizKey;

    /** 记录创建时间，分页默认按其倒序。 */
    private LocalDateTime createTime;
}
