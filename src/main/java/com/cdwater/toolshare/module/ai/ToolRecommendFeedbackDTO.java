package com.cdwater.toolshare.module.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
/**
 * 推荐结果反馈请求体。
 * <p>只接受 1（有帮助）或 2（没帮助），用于评估推荐质量；
 * <p>反馈可重复提交，以最后一次为准；不会触发重新推荐。
 * <p>反馈值由注解限制在 1 到 2，服务层再做一次校验。
 * <p>反馈接口不返回统计结果，居民端只需提示提交成功。
 * <p>反馈记录与推荐历史绑定，用于后续统计降级与排序效果。
 * <p>反馈值只有 1 与 2 两种语义，0 是数据库初始状态，不允许显式提交。
 * 反馈按推荐记录归属校验，居民只能评价自己的推荐。
 */
public class ToolRecommendFeedbackDTO {

    /** 1 有帮助 2 没帮助 */
    // 0 表示未反馈，不允许通过该接口写入，避免覆盖为初始状态。
    @NotNull(message = "请选择反馈结果")
    @Min(value = 1, message = "无效的反馈结果")
    @Max(value = 2, message = "无效的反馈结果")
    private Integer feedback;
}
