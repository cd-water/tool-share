package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.module.message.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息表的 MyBatis-Plus Mapper。
 * <p>承担消息落库、按接收人分页、未读数统计与批量已读更新；
 * <p>所有查询都带 receiver_type + receiver_id 条件，Mapper 本身不负责
 * 权限判断，调用方必须传入来自会话的接收人信息。
 * 幂等依赖 biz_key 唯一索引，不在 Mapper 层额外判断。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
