package com.cdwater.toolshare.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.notify.entity.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息提醒 Mapper
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
