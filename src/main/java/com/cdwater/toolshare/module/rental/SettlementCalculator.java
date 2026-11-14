package com.cdwater.toolshare.module.rental;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 归还结算的纯计算规则：逾期费每小时 5 元且封顶押金，损坏赔偿由验收结果决定，
 * 减免只影响本次结算，不改变费用明细上的原始金额。金额只在这里计算，请求体无法覆盖。
 */
public final class SettlementCalculator {

    public static final BigDecimal HOUR_FEE = new BigDecimal("5");

    private SettlementCalculator() {
    }

    public record Settlement(BigDecimal overdueFee, BigDecimal damageFee, BigDecimal waived,
                             BigDecimal refund, BigDecimal receivable) {
    }

    public static Settlement calculate(Rental rental, LocalDateTime actualReturnTime,
                                       boolean severeDamage, BigDecimal severeDamageFee,
                                       boolean waiveOverdue, boolean waiveDamage) {
        BigDecimal overdueFee = overdueFee(rental, actualReturnTime);
        BigDecimal damageFee = severeDamage
                ? severeDamageFee.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal waived = BigDecimal.ZERO;
        if (waiveOverdue) {
            waived = waived.add(overdueFee);
        }
        if (waiveDamage) {
            waived = waived.add(damageFee);
        }
        BigDecimal netCharges = overdueFee.add(damageFee).subtract(waived);
        BigDecimal refund = rental.getDepositAmount().subtract(netCharges).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal receivable = netCharges.subtract(rental.getDepositAmount()).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return new Settlement(overdueFee, damageFee, waived, refund, receivable);
    }

    /** 逾期费按实际归还时间计算：超过结束时间的小时数向上取整，每小时 5 元，封顶押金。 */
    public static BigDecimal overdueFee(Rental rental, LocalDateTime actualReturnTime) {
        if (!actualReturnTime.isAfter(rental.getEndTime())) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        long seconds = Duration.between(rental.getEndTime(), actualReturnTime).getSeconds();
        long hours = Math.max(1, (seconds + 3599) / 3600);
        return HOUR_FEE.multiply(BigDecimal.valueOf(hours))
                .min(rental.getDepositAmount())
                .setScale(2, RoundingMode.HALF_UP);
    }
}
