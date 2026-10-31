package com.cdwater.toolshare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
