package com.cdwater.toolshare.module.credit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信用分流水实体，对应表 {@code t_user_credit_event}。
 *
 * <p>每条流水代表一次已入账的信用事件。表上存在与业务单据相关的唯一键
 * （租赁单 + 事件类型、评价等），用于在并发或重复调用时保证同一事件
 * 只计一次；插入冲突由服务层捕获并静默忽略。</p>
 */
@Data
@TableName("t_user_credit_event")
public class UserCreditEvent {

    /** 自增主键，仅用于排序与分页游标，不参与业务判断。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分数归属用户 ID，查询时按该列过滤。
     * 该列通常与创建时间组成联合索引，支撑按用户倒序分页。
     */
    private Long userId;

    /** CreditEventType.code() */
    private String eventType;

    /** 实际分数变化，已按 0-100 裁剪 */
    private Integer delta;

    /** 触发事件的租赁单 ID；与 entityType 共同参与唯一键校验。 */
    private Long rentalId;

    /**
     * 触发事件的评价 ID，仅低星评价事件有值。
     * 非评价事件为 null，唯一键设计需容忍 null 值。
     */
    private Long reviewId;

    /** 事件说明，直接展示给用户，便于理解加减分原因。 */
    private String reason;

    /** 流水创建时间，列表按该字段倒序。 */
    private LocalDateTime createTime;
}
