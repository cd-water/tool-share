package com.cdwater.toolshare.module.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_ai_recommendation")
/**
 * AI 工具推荐历史实体，对应表 {@code t_ai_recommendation}。
 * <p>每次推荐都会保存居民需求、过滤条件、推荐结果与是否降级，用于
 * 效果分析与后续反馈闭环。recommendedIds 保存的是当时推荐的工具 ID
 * <p>字段用途：
 * <ul>
 *   <li>question/startTime/endTime/category/maxDeposit：推荐输入；</li>
 *   <li>recommendedIds：推荐输出快照，保持展示顺序；</li>
 *   <li>clickedToolId/feedback：居民行为与质量反馈；</li>
 * <p>该表只追加、不更新推荐输入与结果；点击与反馈是唯一会被覆盖的字段。
 *   <li>degraded：本次是否因外部 AI 不可用而降级。</li>
 * </ul>
 * 快照，不随工具删除而变化；clickedToolId 与 feedback 记录居民行为。
 */
public class AiRecommendation {

    @TableId(type = IdType.AUTO)
    /** 推荐记录主键，数据库自增；作为点击与反馈接口的定位 ID。 */
    private Long id;

    /** 发起推荐的居民用户主键；点击/反馈时用于归属校验。 */
    private Long userId;

    /** 居民原始需求描述，最多 500 个字符。 */
    private String question;

    /** 期望使用开始时间，可为空；与 endTime 同时存在时用于过滤占用工具。 */
    private LocalDateTime startTime;

    /** 期望使用结束时间，可为空；必须晚于 startTime。 */
    private LocalDateTime endTime;

    /** 期望工具类别编码，可为空。 */
    private String category;

    /** 可接受的最大押金，可为空。 */
    private BigDecimal maxDeposit;

    /** 当时推荐的工具 ID，逗号分隔且保持推荐顺序。 */
    private String recommendedIds;

    /** 居民点击过的工具 ID；未点击时为空，多次点击以最后一次为准。 */
    private Long clickedToolId;

    /** 居民反馈：0 未反馈，1 有帮助，2 没帮助。 */
    private Integer feedback;

    /** 本次推荐是否降级：0 正常，1 使用了关键词检索或无大模型重排。 */
    private Integer degraded;

    /** 推荐记录创建时间。 */
    private LocalDateTime createTime;
}
