package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息渠道配置表的 MyBatis-Plus Mapper。
 * <p>更新与插入都由服务层按主键 1 执行，Mapper 不包含业务判断。
 * <p>配置缺失是合法状态，读取方会回退到 application.yml 默认值。
 * <p>表内仅一行配置，主键固定为 1，读写均通过主键完成。
 */
@Mapper
public interface MessageConfigMapper extends BaseMapper<MessageConfig> {
}
