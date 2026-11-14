package com.cdwater.toolshare.module.review;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具评价实体，对应表 {@code t_tool_review}。
 *
 * <p>评价以“租赁单”为唯一业务来源：一条已归还租赁单最多产生一条评价，
 * 数据库通过 rental_id 唯一键兜底并发重复提交。工具与用户字段都是
 * 落库时的快照，查询评价列表无需回表关联工具表或用户表。</p>
 *
 * <p>实体不提供修改语义：评价一经插入即视为最终内容，业务层没有更新
 * 入口。唯一键冲突应被解释为“已评价”，而不是可以覆盖的普通重复。</p>
 */
@Data
@TableName("t_tool_review")
public class ToolReview {

    /** 自增主键，同时作为分页排序的次级键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一单一评，数据库唯一键兜底并发重复提交 */
    private Long rentalId;

    /** 工具与用户均为快照，避免租赁/工具变更影响历史评价 */
    private Long toolId;

    /** 工具名称快照，展示历史评价时不回查工具表。 */
    private String toolName;

    /**
     * 评价用户 ID，用于“我的评价”过滤。
     * 注意它不是快照展示字段，昵称另有 userNickname 保存。
     */
    private Long userId;

    /** 用户昵称快照，展示历史评价时不回查用户表。 */
    private String userNickname;

    /** 1-5 */
    private Integer rating;

    /** 已转为纯文本 */
    private String content;

    /** 逗号分隔，最多 3 张 */
    private String images;

    /** 评价创建时间，列表按该字段倒序。 */
    private LocalDateTime createTime;
}
