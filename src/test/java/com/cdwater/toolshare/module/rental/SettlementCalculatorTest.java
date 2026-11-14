package com.cdwater.toolshare.module.rental;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 结算规则（来自 spec，不重算实现）：逾期每小时 5 元向上取整、封顶押金；
 * 损坏赔偿默认 50 元；减免先冲减应收；超出押金形成应收。
 */
class SettlementCalculatorTest {

    private static final LocalDateTime END = LocalDateTime.of(2026, 5, 1, 18, 0);
    private static final BigDecimal DAMAGE_FEE = new BigDecimal("50");
    private static final BigDecimal DEPOSIT = new BigDecimal("100");

    private static Rental rental(String deposit) {
        Rental rental = new Rental();
        rental.setDepositAmount(new BigDecimal(deposit));
        rental.setStartTime(END.minusDays(2));
        rental.setEndTime(END);
        return rental;
    }

    @Test
    void on_time_intact_returns_full_deposit() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                rental("100"), END.minusMinutes(1), false, DAMAGE_FEE, false, false);

        assertMoney("0.00", settlement.overdueFee());
        assertMoney("0.00", settlement.damageFee());
        assertMoney("100.00", settlement.refund());
        assertMoney("0.00", settlement.receivable());
    }

    @Test
    void one_minute_late_rounds_up_to_one_hour() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                rental("100"), END.plusMinutes(1), false, DAMAGE_FEE, false, false);

        assertMoney("5.00", settlement.overdueFee());
        assertMoney("95.00", settlement.refund());
    }

    @Test
    void overdue_fee_is_capped_at_deposit() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                rental("100"), END.plusHours(30), false, DAMAGE_FEE, false, false);

        assertMoney("100.00", settlement.overdueFee());
        assertMoney("0.00", settlement.refund());
    }

    @Test
    void severe_damage_deducts_fixed_fee() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                rental("100"), END, true, DAMAGE_FEE, false, false);

        assertMoney("50.00", settlement.damageFee());
        assertMoney("50.00", settlement.refund());
    }

    @Test
    void charges_beyond_deposit_become_receivable() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                rental("100"), END.plusHours(30), true, DAMAGE_FEE, false, false);

        assertMoney("100.00", settlement.overdueFee());
        assertMoney("50.00", settlement.damageFee());
        assertMoney("0.00", settlement.refund());
        assertMoney("50.00", settlement.receivable());
    }

    @Test
    void waiver_reduces_charges_and_is_reported_separately() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                rental("100"), END.plusMinutes(1), true, DAMAGE_FEE, true, true);

        assertMoney("5.00", settlement.overdueFee());
        assertMoney("50.00", settlement.damageFee());
        assertMoney("55.00", settlement.waived());
        assertMoney("100.00", settlement.refund());
        assertMoney("0.00", settlement.receivable());
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }
}
