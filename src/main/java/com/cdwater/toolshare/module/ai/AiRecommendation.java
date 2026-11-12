package com.cdwater.toolshare.module.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_ai_recommendation")
public class AiRecommendation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String question;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String category;

    private BigDecimal maxDeposit;

    /** 逗号分隔的工具 ID，按推荐顺序 */
    private String recommendedIds;

    private Long clickedToolId;

    /** 0 未反馈 1 有帮助 2 没帮助 */
    private Integer feedback;

    /** 0 正常 1 降级 */
    private Integer degraded;

    private LocalDateTime createTime;
}
