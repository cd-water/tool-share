package com.cdwater.toolshare.rental.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RentalOrderItem {

    private Long id;

    private String orderCode;

    private Long toolId;

    private String toolCode;

    private String toolName;

    private String userName;

    private String userPhone;

    private String pickupPerson;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal depositAmount;

    private String depositStatus;

    private String status;

    private LocalDateTime pickupTime;

    private LocalDateTime returnTime;

    private String acceptResult;

    private BigDecimal overdueFee;

    private BigDecimal compensationFee;

    private String cancelReason;

    private LocalDateTime createTime;
}
