package com.cdwater.toolshare.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdwater.toolshare.repair.entity.RepairProgress;
import org.apache.ibatis.annotations.Mapper;

/**
 * 维修进度 Mapper
 */
@Mapper
public interface RepairProgressMapper extends BaseMapper<RepairProgress> {
}
