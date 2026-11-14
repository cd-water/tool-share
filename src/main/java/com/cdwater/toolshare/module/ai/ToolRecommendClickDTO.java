package com.cdwater.toolshare.module.ai;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
/**
 * 推荐卡片点击埋点请求体。
 * <p>toolId 必须是本次推荐结果中的工具，服务层会校验推荐记录归属与
 * <p>只有推荐结果中的工具才允许记录，服务层会校验工具 ID 是否命中。
 * <p>点击接口只记录最后一次点击的工具，便于分析最终选择。
 * 工具是否在推荐列表内，防止伪造点击数据。
 */
public class ToolRecommendClickDTO {

    /** 被点击的工具主键。 */
    @NotNull(message = "工具ID不能为空")
    private Long toolId;
}
