package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_notification_pref")
public class UserNotificationPref {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String messageType;

    /** SYSTEM/SMS */
    private String channel;

    /** 0关闭 1开启 */
    private Integer enabled;

    private LocalDateTime updateTime;
}
