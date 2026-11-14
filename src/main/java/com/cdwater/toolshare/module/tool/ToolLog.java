/**
 * 工具操作日志实体文件。
 * <p>对应 t_tool_log 表，按追加方式记录工具生命周期事件：
 * 创建、编辑、审核、状态流转、取件、归还、报废恢复等。
 * <h2>字段语义</h2>
 * <p>fromStatus/toStatus 可为空（创建时无前态）；
 * detail 优先使用备注，否则展示状态迁移文本；
 * operatorId/operatorName 只在管理端登录时记录，用户侧触发可为空。
 * <p>日志只增不改，用于资产审计与问题追溯。
 */
package com.cdwater.toolshare.module.tool;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具操作日志实体，对应数据库表 t_tool_log。
 * <p>每次创建、编辑、审核、状态流转、报废恢复都会追加一条；
 * 日志只增不改，用于资产审计与问题追溯。
 */
@Data
@TableName("t_tool_log")
public class ToolLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属工具 id
    private Long toolId;

    // 动作编码：CREATE / UPDATE / AUDIT / STATUS_CHANGE / PICKUP / RETURN / RESTORE 等
    private String action;

    // 变更前状态；创建时为 null
    private String fromStatus;

    // 变更后状态
    private String toStatus;

    // 可读描述；优先使用备注，否则展示状态迁移
    private String detail;

    // 操作的管理端账号 id；用户侧触发的状态变化可能为空
    private Long operatorId;

    // 操作人用户名快照，避免账号改名后历史日志失真
    private String operatorName;

    // 原始备注，如审核意见、恢复原因
    private String remark;

    // 日志创建时间
    private LocalDateTime createTime;
}
