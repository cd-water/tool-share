package com.cdwater.toolshare.module.review;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 评价 Mapper。
 *
 * <p>除基础 CRUD 外，提供两个评分聚合查询：列表页按一批工具 ID 一次性
 * 聚合，详情页按单个工具聚合。聚合只读取评价表，不联表，因此即使工具
 * 已被删除也能保留历史评分。</p>
 *
 * <p>列表聚合使用 IN + GROUP BY，避免为每个工具单独查询造成 N+1；
 * 没有评价的工具不会出现在结果集中，调用方需按 toolId 做缺省处理。
 * 平均分使用数据库 ROUND(..., 1)，保证前后端展示精度一致。</p>
 *
 * <p>两个查询都只读，不修改评价数据。SQL 中的参数均通过 MyBatis
 * 占位符绑定，不拼接用户输入。</p>
 *
 * <p>聚合结果不缓存：评价数量变化频繁，缓存收益有限且容易与详情页
 * 展示不一致。若后续需要优化，应先确认评价表上的 tool_id 索引。
 * 两个方法均不使用事务，由调用方决定是否与其它查询共享连接。</p>
 *
 * <p>空集合调用方需自行处理，避免 IN () 语法错误。</p>
 */
@Mapper
public interface ToolReviewMapper extends BaseMapper<ToolReview> {

    // 批量评分摘要：调用方通常传入当前页的工具 ID 集合。
    // 集合为空时 IN () 可能产生非法 SQL，调用方应先判空再调用。
    // 返回顺序不保证与入参一致，调用方应按 toolId 建映射。
    // ratingAvg 与 reviewCount 对没有评价的工具没有对应行。
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

    // 单工具摘要：工具没有评价时返回 null，而不是空聚合行。
    // 详情页应把 null 解释为“暂无评分”，不要显示成 0 分。
    // 该查询同样只读，可与评价分页并行执行，二者互不依赖。
    // 参数 toolId 由服务层保证非空。
    // 返回的 ratingAvg 为 null 时，前端应展示“暂无评分”。
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
