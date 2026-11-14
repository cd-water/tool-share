/**
 * 创建租赁预约请求体文件。
 * <p>只包含业务事实：工具 id、租期、取件人与到期提醒提前量。
 * 押金、单号、初始状态与联系信息全部由服务端确定，请求体无法覆盖。
 * <h2>校验</h2>
 * <p>字段非空；服务层还会校验开始时间晚于当前、结束晚于开始、
 * 用户已完善实名信息、工具审核通过且可用、档期无冲突。
 * <h2>提醒</h2>
 * <p>remindBeforeMinutes 为 0 表示不提醒；null 使用服务端默认值（1440 分钟），
 * 且不超过租期时长。
 * <p>调用方：UserRentalController#create。
 */
package com.cdwater.toolshare.module.rental;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建租赁预约请求。
 * <p>只包含业务事实：工具、租期、取件人与提醒提前量；
 * 押金、单号与初始状态全部由服务端确定。
 */
@Data
public class RentalCreateDTO {

    @NotNull(message = "请选择工具")
    // 目标工具 id，必填
    private Long toolId;

    @NotNull(message = "请选择开始时间")
    // 租期开始，必须晚于当前时间
    private LocalDateTime startTime;

    @NotNull(message = "请选择结束时间")
    // 租期结束，必须晚于开始时间
    private LocalDateTime endTime;

    @NotBlank(message = "请填写取件人")
    @Size(max = 64, message = "取件人不能超过 64 个字符")
    // 实际取件人，必填；可能与租赁人不同
    private String pickupPerson;

    /**
     * 到期提醒：提前多少分钟提醒，0 表示不提醒；为空时使用配置默认值（1440 分钟）。
     */
    @Min(value = 0, message = "提醒提前量不能为负数")
    @Max(value = 10080, message = "提醒提前量不能超过 7 天")
    // 到期提醒提前量（分钟），0 表示不提醒，null 使用服务端默认值
    private Integer remindBeforeMinutes;
}
