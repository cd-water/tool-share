package com.cdwater.toolshare.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.notify.entity.NotifyConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 默认提醒方式配置 Mapper
 */
@Mapper
public interface NotifyConfigMapper extends BaseMapper<NotifyConfig> {
}
