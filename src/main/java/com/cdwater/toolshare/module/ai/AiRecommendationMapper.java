package com.cdwater.toolshare.module.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 推荐历史表的 MyBatis-Plus Mapper。
 * <p>用于推荐记录落库、点击/反馈更新以及归属校验查询；不包含自定义
 * <p>推荐历史只追加，不在业务上删除，便于长期效果分析。
 * <p>推荐记录的点击与反馈更新都按主键进行，归属校验在服务层完成。
 * 统计 SQL，效果分析可在此按需扩展。
 */
@Mapper
public interface AiRecommendationMapper extends BaseMapper<AiRecommendation> {
}
