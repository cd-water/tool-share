package com.cdwater.toolshare.module.credit;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信用分流水视图对象。
 *
 * <p>用于用户端“信用分”页面展示最近的加减分记录。一条记录对应一次
 * 业务事件（归还、逾期、严重损坏或低星评价），其中的 delta 是写入时
 * 经过 0-100 区间裁剪后的实际变化，因此前端直接把 delta 相加即可得到
 * 当前分数，无需再次套用规则。</p>
 */
@Data
public class CreditEventVO {

    /** 流水主键，仅用于前端列表 key。 */
    private Long id;

    /** 事件类型代码，取值见 {@code CreditEventType.code()}。 */
    private String eventType;

    /** 事件类型的中文名称；未知代码时回退显示原始代码。 */
    private String eventLabel;

    /** 实际分数变化，已按 0-100 裁剪 */
    private Integer delta;

    /** 关联的租赁单 ID，归还/逾期类事件必填。 */
    private Long rentalId;

    /** 关联的评价 ID，仅低星评价事件有值。 */
    private Long reviewId;

    /** 面向用户展示的事件说明，说明触发单据与原因。 */
    private String reason;

    /** 流水创建时间，列表按该字段倒序排列。 */
    private LocalDateTime createTime;
}
