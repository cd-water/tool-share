package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_notification_pref")
/**
 * 居民提醒偏好实体，对应表 {@code t_user_notification_pref}。
 * <p>按「用户 + 消息类型」唯一：记录某居民是否接收某类提醒以及使用
 * 哪个渠道。记录缺失时由服务层按默认渠道、开启状态处理，因此本表
 * <p>唯一约束为 (user_id, message_type)。enabled=0 表示关闭该类型提醒，
 * <p>updateTime 由数据库自动维护，服务层不显式赋值。
 * 服务层会在写入消息前直接返回；channel 在关闭时仍保留，便于恢复。
 * <p>服务层在读取时会为缺失类型补齐默认值。
 * 只保存居民显式修改过的偏好。
 */
public class UserNotificationPref {
    // 按用户与消息类型唯一。
    // enabled=0 表示关闭该类型提醒。
    // channel 在关闭时仍保留，便于恢复。
    // 缺失记录由服务层按默认值补齐。

    @TableId(type = IdType.AUTO)
    /** 偏好记录主键，数据库自增。 */
    private Long id;

    /** 居民用户主键。 */
    private Long userId;

    /** 消息类型编码，与 MessageType 对应。 */
    private String messageType;

    /** 该类型使用的投递渠道：SYSTEM 或 SMS。 */
    private String channel;

    /** 是否接收该类型提醒：0 关闭，1 开启。 */
    private Integer enabled;

    /** 最近一次修改时间。 */
    private LocalDateTime updateTime;
}
