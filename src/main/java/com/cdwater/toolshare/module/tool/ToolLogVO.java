/**
 * 工具操作日志视图文件。
 * <p>字段与 ToolLog 实体一一对应，供管理端展示。
 * 包含动作编码、前后状态、可读描述、操作人快照、原始备注与时间；
 * 不在视图层做状态翻译，避免与工具状态字典产生第二套语义。
 * <p>调用方：ToolService#logs 经 ToolLogVoMapper 转换。
 */
package com.cdwater.toolshare.module.tool;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具操作日志视图。
 * <p>字段与实体一一对应，仅用于管理端展示，不包含内部状态判断逻辑。
 */
@Data
public class ToolLogVO {

    // 日志主键
    private Long id;
    // 工具 id
    private Long toolId;
    // 动作编码
    private String action;
    // 变更前状态
    private String fromStatus;
    // 变更后状态
    private String toStatus;
    // 可读描述
    private String detail;
    // 操作人 id
    private Long operatorId;
    // 操作人用户名快照
    private String operatorName;
    // 原始备注
    private String remark;
    // 日志时间
    private LocalDateTime createTime;
}
