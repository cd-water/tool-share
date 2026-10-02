package com.cdwater.toolshare.rental.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PayResponse {

    private String payCode;

    private BigDecimal amount;

    private LocalDateTime payTime;
}
