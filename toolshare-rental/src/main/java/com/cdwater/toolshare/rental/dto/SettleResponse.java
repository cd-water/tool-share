package com.cdwater.toolshare.rental.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 归还结算：refundAmount = 押金 - overdueFee - compensationFee（下限 0）
 */
@Data
public class SettleResponse {

    private BigDecimal overdueFee;

    private BigDecimal compensationFee;

    private BigDecimal refundAmount;
}
