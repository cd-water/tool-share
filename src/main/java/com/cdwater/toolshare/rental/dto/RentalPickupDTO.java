package com.cdwater.toolshare.rental.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取件登记入参
 */
@Data
public class RentalPickupDTO {

    /** 取件人（可修改为代取人，不传则用预约时填写） */
    @Size(max = 50, message = "取件人最长 50 字")
    private String pickupName;
}
