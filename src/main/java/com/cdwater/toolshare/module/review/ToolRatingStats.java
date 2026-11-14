package com.cdwater.toolshare.module.review;

import lombok.Data;

import java.math.BigDecimal;

/** 工具评分摘要，分页时按 toolIds 一次聚合查询。 */
@Data
public class ToolRatingStats {

    private Long toolId;

    private BigDecimal ratingAvg;

    private Long reviewCount;
}
