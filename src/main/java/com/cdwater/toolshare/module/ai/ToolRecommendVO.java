package com.cdwater.toolshare.module.ai;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
/**
 * AI 工具推荐响应对象。
 * <p>summary 是一句话建议，items 是最终推荐列表；degraded 提示本次是否
 * 因外部 AI 不可用或输出不合法而降级，前端可据此弱化「AI 推荐」标识。
 * <p>recommendationId 为空时，前端应禁用点击与反馈入口。
 * <p>degraded 为 true 时，items 可能来自关键词召回或启发式排序。
 * recommendationId 用于点击埋点与反馈接口，为空表示历史保存失败。
 */
public class ToolRecommendVO {

    /** 推荐记录主键；保存历史失败时为空，前端应禁用点击与反馈。 */
    private Long recommendationId;

    /** 一句话推荐摘要，由大模型生成或使用默认文案。 */
    private String summary;

    /** 推荐工具列表，按推荐顺序排列，最多 5 条。 */
    private List<Item> items;

    /** 是否发生降级：关键词检索或无大模型重排时为 true。 */
    private boolean degraded;

    @Data
    /**
     * 单个推荐工具。
     * <p>只包含卡片展示所需字段；reason 是推荐理由，可能来自大模型，
     * 也可能使用默认文案。
     */
    public static class Item {
        /** 工具主键，点击埋点使用。 */
        private Long toolId;
        /** 工具名称。 */
        private String name;
        /** 工具类别中文名。 */
        private String categoryName;
        /** 工具型号，可为空。 */
        private String model;
        /** 工具押金。 */
        private BigDecimal deposit;
        /** 工具主图地址。 */
        private String imageUrl;
        /** 工具状态中文名。 */
        private String statusName;
        /** 推荐理由，不超过 40 字。 */
        private String reason;
        /** 工具存放位置，便于居民取用。 */
        private String storageArea;
    }
}
