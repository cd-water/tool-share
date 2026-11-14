package com.cdwater.toolshare.module.review;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ToolReviewMapper extends BaseMapper<ToolReview> {

    /** 工具列表的评分摘要：一次 IN 查询，避免逐工具 N+1。 */
    @Select("""
            <script>
            SELECT tool_id AS toolId,
                   ROUND(AVG(rating), 1) AS ratingAvg,
                   COUNT(*) AS reviewCount
            FROM t_tool_review
            WHERE tool_id IN
            <foreach collection="toolIds" item="toolId" open="(" separator="," close=")">#{toolId}</foreach>
            GROUP BY tool_id
            </script>
            """)
    List<ToolRatingStats> selectStatsByToolIds(@Param("toolIds") Collection<Long> toolIds);

    /** 单个工具详情/工具评价页的评分摘要。 */
    @Select("""
            SELECT tool_id AS toolId,
                   ROUND(AVG(rating), 1) AS ratingAvg,
                   COUNT(*) AS reviewCount
            FROM t_tool_review
            WHERE tool_id = #{toolId}
            """)
    ToolRatingStats selectStatsByToolId(@Param("toolId") Long toolId);
}
