package com.cdwater.toolshare.module.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
/**
 * AI 工具推荐请求参数。
 * <p>question 是唯一的必填项，描述居民想做的事情；其余字段都是可选
 * 过滤条件。时间条件必须同时填写或同时为空，类别必须属于工具类别
 * <p>limit 默认 5，前端可调低但不能超过服务端配置上限。
 * <p>时间条件同时存在时才参与占用过滤；类别与押金条件相互独立。
 * 字典，押金上限不能为负。limit 会被服务端再次钳制到配置上限。
 */
public class ToolRecommendDTO {

    /**
     * 自然语言需求描述，最多 500 字。
     * <p>该文本同时用于向量召回、关键词兜底与大模型重排提示词。
     */
    @NotBlank(message = "请描述您的需求")
    @Size(max = 500, message = "需求描述不能超过 500 字")
    private String question;

    /** 期望开始时间，与 endTime 同时为空或同时填写 */
    // 时间段用于排除在租或已预约的工具，避免推荐已占用的资源。
    private LocalDateTime startTime;

    /** 期望结束时间 */
    // 服务层校验必须晚于开始时间，否则返回 400。
    private LocalDateTime endTime;

    /**
     * 期望工具类别编码，可选；必须属于工具类别字典。
     * <p>类别过滤在数据库查询与候选过滤两处都会生效。
     */
    @Size(max = 16, message = "工具类别不能超过 16 个字符")
    @Schema(allowableValues = {"WX", "YY", "QJ", "QT"})
    private String category;

    /**
     * 可接受的最大押金，可选；非空时必须大于等于 0。
     * <p>过滤时按工具押金与上限比较，押金为空的工具视为不满足条件。
     */
    @DecimalMin(value = "0", message = "押金上限不能为负数")
    private BigDecimal maxDeposit;

    /**
     * 期望返回的推荐数量，默认 5，范围 1 到 5。
     * <p>最终数量还会被管理端配置与硬上限再次钳制。
     */
    @Min(value = 1, message = "推荐数量至少为 1")
    @Max(value = 5, message = "推荐数量最多为 5")
    private Integer limit = 5;
}
