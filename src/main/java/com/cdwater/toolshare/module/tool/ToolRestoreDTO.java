/**
 * 报废工具恢复请求体文件。
 * <p>只有一个必填的 reason 字段，最长 500 字。
 * <h2>用途</h2>
 * <p>恢复原因会写入工具操作日志，形成“报废 -> 恢复”的审计链。
 * 服务层还会再次校验原因非空，并检查活动租赁与未完成报修。
 * <p>调用方：AdminToolController#restore。
 */
package com.cdwater.toolshare.module.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 报废工具恢复请求。
 * <p>恢复原因必填，会写入工具操作日志，便于审计。
 */
@Data
public class ToolRestoreDTO {

    @NotBlank(message = "恢复原因不能为空")
    @Size(max = 500, message = "恢复原因不能超过 500 个字符")
    // 恢复原因，最长 500 字
    private String reason;
}
