package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 单行消息渠道配置，主键固定为 1。 */
@Data
@TableName("t_message_config")
/**
 * 消息渠道配置实体，对应表 {@code t_message_config}。
 * <p>全表只保留一行，主键固定为 1；读取方在记录缺失时回退到
 * <p>defaultChannel 只允许 SYSTEM/SMS；smsEnabled 是短信总开关，关闭后
 * 所有居民偏好中的 SMS 都会回退到站内信。两个字段互不覆盖：默认渠道
 * <p>该表只允许一行，主键固定为 1，由服务层保证 upsert 语义。
 * 决定未单独配置偏好的居民，短信开关决定短信能力是否可用。
 * <p>defaultChannel 只允许 SYSTEM/SMS；smsEnabled 是短信总开关。
 * application.yml 的默认值，因此该表允许为空。
 */
public class MessageConfig {
    // 全表只保留一行，主键固定为 1。
    // defaultChannel 只允许 SYSTEM/SMS。
    // smsEnabled 是短信总开关。
    // 记录缺失时读取方回退到 yml 默认值。

    @TableId(type = IdType.INPUT)
    /** 固定主键，始终为 1。 */
    private Long id;

    /** 默认投递渠道：SYSTEM 或 SMS。 */
    private String defaultChannel;

    /** 短信总开关：0 关闭，1 开启。 */
    private Integer smsEnabled;

    /** 配置最后更新时间。 */
    private LocalDateTime updateTime;
}
