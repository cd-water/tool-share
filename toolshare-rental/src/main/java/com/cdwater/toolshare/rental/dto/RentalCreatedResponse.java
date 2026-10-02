package com.cdwater.toolshare.rental.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class RentalCreatedResponse {

    private Long id;

    private String orderCode;

    private BigDecimal depositAmount;

    private String status;
}
