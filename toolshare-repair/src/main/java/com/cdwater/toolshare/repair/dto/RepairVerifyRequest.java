package com.cdwater.toolshare.repair.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 核验：CONFIRMED / CHECKING / REJECTED（REJECTED 时 rejectReason 必填）
 */
@Data
public class RepairVerifyRequest {

    @NotBlank(message = "核验结果不能为空")
    private String result;

    private String rejectReason;
}
