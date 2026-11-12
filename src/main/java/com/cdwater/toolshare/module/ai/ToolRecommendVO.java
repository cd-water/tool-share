package com.cdwater.toolshare.module.ai;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ToolRecommendVO {

    private Long recommendationId;

    /** 一句话建议 */
    private String summary;

    private List<Item> items;

    /** 是否走了降级（关键词检索或无大模型重排） */
    private boolean degraded;

    @Data
    public static class Item {
        private Long toolId;
        private String name;
        private String categoryName;
        private String model;
        private BigDecimal deposit;
        private String imageUrl;
        private String statusName;
        private String reason;
        private String storageArea;
    }
}
