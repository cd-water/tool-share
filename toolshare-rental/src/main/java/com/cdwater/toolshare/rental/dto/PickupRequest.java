package com.cdwater.toolshare.rental.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PickupRequest {

    @NotBlank(message = "取件人不能为空")
    private String pickupPerson;
}
