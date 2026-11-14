package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 居民提醒偏好表的 MyBatis-Plus Mapper。
 * <p>偏好按「用户 + 消息类型」唯一，服务层使用条件查询完成 upsert，
 * <p>服务层按用户与类型条件查询后决定插入或更新。
 * <p>唯一约束 (user_id, message_type) 保证同一居民同一类型只有一条偏好。
 * 无需自定义 SQL。
 */
@Mapper
public interface UserNotificationPrefMapper extends BaseMapper<UserNotificationPref> {
}
