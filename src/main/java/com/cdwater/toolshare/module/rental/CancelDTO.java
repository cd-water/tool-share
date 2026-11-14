/**
 * 取消租赁请求体文件。
 * <p>只包含可选的原因字段，最长 500 字。
 * 原因为空时服务端使用默认文案（用户取消 / 工作人员取消），
 * 保证取消原因字段始终可读。
 * <p>调用方：AdminRentalController#cancel、UserRentalController#cancel。
 */
package com.cdwater.toolshare.module.rental;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取消租赁请求体。
 * <p>原因可选；为空时服务端使用默认文案（用户取消 / 工作人员取消）。
 */
@Data
public class CancelDTO {

    @Size(max = 500, message = "取消原因不能超过 500 个字符")
    // 取消原因，最长 500 字
    private String reason;
}
