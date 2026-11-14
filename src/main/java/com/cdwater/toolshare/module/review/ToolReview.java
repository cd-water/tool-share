package com.cdwater.toolshare.module.review;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_tool_review")
public class ToolReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一单一评，数据库唯一键兜底并发重复提交 */
    private Long rentalId;

    /** 工具与用户均为快照，避免租赁/工具变更影响历史评价 */
    private Long toolId;

    private String toolName;

    private Long userId;

    private String userNickname;

    /** 1-5 */
    private Integer rating;

    /** 已转为纯文本 */
    private String content;

    /** 逗号分隔，最多 3 张 */
    private String images;

    private LocalDateTime createTime;
}
