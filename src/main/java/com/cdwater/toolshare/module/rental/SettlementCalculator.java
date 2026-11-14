/**
 * 归还结算纯计算文件。
 * <p>所有方法无状态、无数据库访问，便于单元测试与复用。
 * <h2>规则</h2>
 * <ul>
 *   <li>逾期费：超过结束时间的小时数向上取整，每小时 5 元，封顶押金；</li>
 *   <li>损坏赔偿：当前只有严重损坏产生固定赔偿；</li>
 *   <li>减免：只影响本次结算，不改变费用明细上的原始金额；</li>
 *   <li>退款与应收：押金先抵扣净应收，余额退款，不足形成应收，二者都非负。</li>
 * </ul>
 * <h2>金额精度</h2>
 * <p>统一保留两位小数，使用 HALF_UP；金额只在这里计算，请求体无法覆盖。
 * <p>调用方：RentalService#returnRental。
 */
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

    // 逾期费单价：每小时 5 元，不足一小时按一小时计
    public static final BigDecimal HOUR_FEE = new BigDecimal("5");

    private SettlementCalculator() {
    }

    /**
     * 一次归还结算的完整结果。
     *
     * @param overdueFee 逾期费原始金额（减免前）
     * @param damageFee  损坏赔偿原始金额（减免前）
     * @param waived     本次减免总额
     * @param refund     应退押金，最小为 0
     * @param receivable 押金不足时的应收金额，最小为 0
     */
    public record Settlement(BigDecimal overdueFee, BigDecimal damageFee, BigDecimal waived,
                             BigDecimal refund, BigDecimal receivable) {
    }

    /**
     * 计算归还结算。
     * <p>净应收 = 逾期费 + 损坏赔偿 - 减免；押金先抵扣净应收，
     * 剩余部分退款，不足部分形成应收。退款与应收互斥且都非负。
     * 所有金额统一保留两位小数，使用 HALF_UP 四舍五入。
     *
     * @param rental            租赁单，提供计划结束时间与押金
     * @param actualReturnTime  实际归还时间
     * @param severeDamage      验收是否为严重损坏
     * @param severeDamageFee   严重损坏赔偿金额
     * @param waiveOverdue      是否全额减免逾期费
     * @param waiveDamage       是否全额减免损坏赔偿
     * @return 结算结果
     */
    public static Settlement calculate(Rental rental, LocalDateTime actualReturnTime,
                                       boolean severeDamage, BigDecimal severeDamageFee,
                                       boolean waiveOverdue, boolean waiveDamage) {
        // 逾期费只按实际归还时间计算，提前归还为 0
        BigDecimal overdueFee = overdueFee(rental, actualReturnTime);
        // 当前规则只有严重损坏产生赔偿，轻微损坏不收费
        BigDecimal damageFee = severeDamage
                ? severeDamageFee.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal waived = BigDecimal.ZERO;
        // 减免只影响本次结算，不修改费用明细上的原始金额
        if (waiveOverdue) {
            waived = waived.add(overdueFee);
        }
        // 损坏赔偿减免独立累计到减免总额
        if (waiveDamage) {
            waived = waived.add(damageFee);
        }
        // 净应收可能为 0 或正数；减免不会产生负数应收
        BigDecimal netCharges = overdueFee.add(damageFee).subtract(waived);
        // 押金抵扣净应收后的余额退款；不足时不产生负退款
        BigDecimal refund = rental.getDepositAmount().subtract(netCharges).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        // 净应收超过押金的部分转为应收，需另行追缴
        BigDecimal receivable = netCharges.subtract(rental.getDepositAmount()).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return new Settlement(overdueFee, damageFee, waived, refund, receivable);
    }

    // 不足一小时按一小时计；逾期费最高不超过押金，避免出现押金为负。
    // 恰好按时归还（归还时间等于结束时间）不收费。
    /** 逾期费按实际归还时间计算：超过结束时间的小时数向上取整，每小时 5 元，封顶押金。 */
    public static BigDecimal overdueFee(Rental rental, LocalDateTime actualReturnTime) {
        // 未超过结束时间视为按时归还
        if (!actualReturnTime.isAfter(rental.getEndTime())) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        // 秒级差值，避免跨分钟边界时少算一小时
        long seconds = Duration.between(rental.getEndTime(), actualReturnTime).getSeconds();
        // 向上取整且至少 1 小时；正数除法避免浮点误差
        long hours = Math.max(1, (seconds + 3599) / 3600);
        // 小时数 × 单价，再以押金封顶
        return HOUR_FEE.multiply(BigDecimal.valueOf(hours))
                .min(rental.getDepositAmount())
                .setScale(2, RoundingMode.HALF_UP);
    }
}
