package com.cdwater.toolshare.module.review;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价视图对象。
 *
 * <p>工具名与用户昵称均是评价落库时的快照，因此即使用户后来改名或工具
 * 被编辑，历史评价仍展示当时的信息。图片在实体中以逗号分隔存储，返回
 * 前端时转换为数组。</p>
 */
@Data
public class ReviewVO {

    /** 评价主键。 */
    private Long id;

    /** 关联租赁单 ID，用于从评价跳转到订单详情。 */
    private Long rentalId;

    /** 工具 ID，用于跳转到工具详情页。 */
    private Long toolId;

    /** 工具名称快照，避免工具改名影响历史评价。 */
    private String toolName;

    /** 评价用户 ID。 */
    private Long userId;

    /** 用户昵称快照，避免用户改名影响历史评价。 */
    private String userNickname;

    /** 1-5 星评分。 */
    private Integer rating;

    /** 已净化的纯文本评价内容。 */
    private String content;

    /** 图片地址列表，最多 3 张；无图时为空列表。 */
    private List<String> images;

    /** 评价创建时间，列表按该字段倒序。 */
    private LocalDateTime createTime;
}
