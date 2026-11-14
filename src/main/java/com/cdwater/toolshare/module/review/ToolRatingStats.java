package com.cdwater.toolshare.module.review;

import lombok.Data;

import java.math.BigDecimal;

/** 工具评分摘要，分页时按 toolIds 一次聚合查询。 */
@Data
public class ToolRatingStats {

    /**
     * 工具 ID，对应当前页中的某个工具。
     * 批量查询时用于把摘要回填到正确的工具上。
     */
    private Long toolId;

    /**
     * 平均评分，保留一位小数；没有评价的工具不会出现在结果中。
     * 使用 BigDecimal 避免浮点误差影响展示排序。
     */
    private BigDecimal ratingAvg;

    /**
     * 评价条数，用于前端展示“x 条评价”。
     * 与平均分同时聚合，避免额外一次 count 查询。
     */
    private Long reviewCount;
}
