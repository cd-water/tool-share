/**
 * 工具状态修改请求体文件。
 * <p>只携带目标状态编码，合法值由 ToolStatus 字典与 ToolStateMachine 共同约束。
 * <h2>注意</h2>
 * <p>状态修改不是简单赋值：服务层会检查活动租赁与未完成报修，
 * 并使用乐观锁防止并发覆盖。
 * <p>调用方：AdminToolController#updateStatus。
 */
package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工具状态修改请求。
 * <p>只携带目标状态编码，合法性由字典与状态机共同校验。
 */
@Data
public class ToolStatusDTO {

    @NotBlank(message = "工具状态不能为空")
    @Size(max = 20, message = "工具状态不能超过 20 个字符")
    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    // 目标状态：AVAILABLE / RENTED / MAINTENANCE / SCRAPPED
    private String status;
}
