package com.cdwater.toolshare.module.rental;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelDTO {

    @Size(max = 500, message = "取消原因不能超过 500 个字符")
    private String reason;
}
