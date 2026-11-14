package com.cdwater.toolshare.module.rental;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 租赁结算计算器单元测试。
 * <p>
 * 测试范围：逾期费、损坏赔偿、减免与押金退还/应收的金额计算规则。
 * 依赖环境：纯计算类，无数据库、无请求上下文。
 * 关键不变量：逾期按每小时 5 元向上取整并封顶押金；严重损坏默认 50 元；
 * 减免只影响实际收取并单独上报；超出押金的差额转为应收。
 * 本测试按规格独立断言结果，不复制实现中的计算步骤。
 */
class SettlementCalculatorTest {

    /** 统一定义的约定归还时间，所有用例以此为基准偏移。 */
    private static final LocalDateTime END = LocalDateTime.of(2026, 5, 1, 18, 0);
    /** 严重损坏的固定赔偿金额。 */
    private static final BigDecimal DAMAGE_FEE = new BigDecimal("50");
    /** 默认押金金额，用于构造标准租赁单。 */
    private static final BigDecimal DEPOSIT = new BigDecimal("100");

    /**
     * 辅助方法：构造一个租期两天、指定押金的租赁单。
     * 只填充结算所需字段，避免依赖完整业务对象。
     */
    private static Rental rental(String deposit) {
        // 只填充结算需要的押金与租期字段，其余字段保持默认。
        // 这样用例不依赖租赁单的完整创建流程。
        Rental rental = new Rental();
        // 押金以字符串构造 BigDecimal，避免 double 精度问题。
        rental.setDepositAmount(new BigDecimal(deposit));
        // 租期固定为两天，便于按小时推算逾期时长。
        rental.setStartTime(END.minusDays(2));
        // 约定归还时间统一使用 END，所有用例只偏移实际归还时间。
        rental.setEndTime(END);
        return rental;
    }

    /**
     * 场景：按时归还且工具完好。
     * 前置条件：实际归还时间早于约定结束时间。
     * 断言目标：逾期费与赔偿均为 0，押金全额退还，无应收。
     */
    @Test
    void on_time_intact_returns_full_deposit() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                // 入参依次为：租赁单、实际归还时间、是否严重损坏、损坏赔偿标准、是否减免逾期费、是否减免赔偿。
                // 这里提前一分钟归还且工具完好，是结算的最简正例。
                rental("100"), END.minusMinutes(1), false, DAMAGE_FEE, false, false);

        // 未逾期，逾期费必须为 0。
        // 正例中逾期费为 0，是后续费用叠加的基线。
        assertMoney("0.00", settlement.overdueFee());
        // 工具完好，不产生任何赔偿。
        // 未勾选严重损坏时赔偿恒为 0。
        assertMoney("0.00", settlement.damageFee());
        // 减免后实际收取为 0，押金全额退还。
        // 与「已减免」分开展示，避免用户误以为没有产生费用。
        // 押金全额退还，说明两项费用都没有侵蚀押金。
        // 五项费用字段互相独立，退款不直接改写原始费用。
        assertMoney("100.00", settlement.refund());
        // 没有超出押金的费用，应收为 0。
        assertMoney("0.00", settlement.receivable());
    }

    /**
     * 场景：仅迟到一分钟。
     * 前置条件：逾期不足一小时按一小时计。
     * 断言目标：逾期费 5 元，退还 95 元。
     */
    @Test
    void one_minute_late_rounds_up_to_one_hour() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                // 仅迟到一分钟，用于验证不足一小时按一小时向上取整。
                // 无损坏、无减免，便于单独观察逾期费效果。
                rental("100"), END.plusMinutes(1), false, DAMAGE_FEE, false, false);

        // 每小时 5 元，一分钟也按一小时计，取整方向对出租方保守。
        // 原始逾期费仍按规则计算，不因减免而归零。
        assertMoney("5.00", settlement.overdueFee());
        // 押金 100 扣除 5 元逾期费后原路退还。
        // 退款 = 押金 - 实际收取费用，无其他隐藏扣款。
        assertMoney("95.00", settlement.refund());
    }

    /**
     * 场景：逾期 30 小时，远超押金可覆盖范围。
     * 前置条件：逾期费以押金为上限。
     * 断言目标：逾期费封顶 100 元，押金不再退还。
     */
    @Test
    void overdue_fee_is_capped_at_deposit() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                // 逾期 30 小时，理论上逾期费 150 元，远超 100 元押金。
                // 该用例验证封顶规则而非简单相乘。
                rental("100"), END.plusHours(30), false, DAMAGE_FEE, false, false);

        // 逾期费以押金为上限，避免产生负退款。
        assertMoney("100.00", settlement.overdueFee());
        // 押金被逾期费全部抵扣，退还为 0。
        assertMoney("0.00", settlement.refund());
    }

    /**
     * 场景：按时归还但存在严重损坏。
     * 前置条件：严重损坏赔偿采用固定 50 元档位。
     * 断言目标：赔偿 50 元，剩余押金 50 元退还。
     */
    @Test
    void severe_damage_deducts_fixed_fee() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                // 按时归还但工具严重损坏，单独验证固定赔偿档位。
                // 损坏赔偿不随押金或租期变化，便于规则解释。
                rental("100"), END, true, DAMAGE_FEE, false, false);

        // 严重损坏按约定的固定 50 元收取。
        // 赔偿档位由入参 DAMAGE_FEE 决定，测试与生产配置解耦。
        assertMoney("50.00", settlement.damageFee());
        // 剩余 50 元押金退还给用户。
        // 押金扣除赔偿后的余额，验证费用与押金的联动。
        assertMoney("50.00", settlement.refund());
    }

    /**
     * 场景：同时发生逾期封顶与严重损坏。
     * 前置条件：费用合计 150 元，超过 100 元押金。
     * 断言目标：押金全额抵扣后仍有 50 元应收，退款为 0。
     */
    @Test
    void charges_beyond_deposit_become_receivable() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                // 同时触发逾期封顶与严重损坏，验证多费用叠加后的应收逻辑。
                // 费用合计 150 元，押金只有 100 元。
                rental("100"), END.plusHours(30), true, DAMAGE_FEE, false, false);

        assertMoney("100.00", settlement.overdueFee());
        assertMoney("50.00", settlement.damageFee());
        assertMoney("0.00", settlement.refund());
        // 超出押金的 50 元转为对用户的应收，不能从退款中倒扣。
        // 前端需把该项展示为待补缴金额。
        // 应收仅记录超出押金的部分，不包含押金本身。
        assertMoney("50.00", settlement.receivable());
    }

    /**
     * 场景：逾期与损坏均被管理员减免。
     * 前置条件：原始费用仍按规则计算，减免单独统计。
     * 断言目标：减免总额 55 元、押金全额退还、无应收，
     * 便于前端分别展示“应收”与“已减免”。
     */
    @Test
    void waiver_reduces_charges_and_is_reported_separately() {
        SettlementCalculator.Settlement settlement = SettlementCalculator.calculate(
                // 逾期与损坏均被管理员减免，验证减免只影响实收。
                // 原始费用仍按规则计算并单独上报，便于审计。
                rental("100"), END.plusMinutes(1), true, DAMAGE_FEE, true, true);

        assertMoney("5.00", settlement.overdueFee());
        assertMoney("50.00", settlement.damageFee());
        // 减免总额为逾期 5 元加损坏 50 元，共 55 元。
        // waived 字段只做展示，不参与押金抵扣。
        // 减免与应收互斥：被减免的部分不再转为应收。
        // 减免金额单独汇总，便于运营统计与用户账单解释。
        assertMoney("55.00", settlement.waived());
        assertMoney("100.00", settlement.refund());
        assertMoney("0.00", settlement.receivable());
    }

    /**
     * 辅助方法：按数值比较金额，避免 BigDecimal 精度差异造成误报。
     * 失败信息同时输出期望值与实际值，便于定位结算偏差。
     */
    private static void assertMoney(String expected, BigDecimal actual) {
        // 用 compareTo 而非 equals，忽略 BigDecimal 的 scale 差异。
        // 失败信息同时输出期望值与实际值，便于定位结算偏差。
        // 金额比较不关心 100 与 100.00 的小数位差异。
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }
}
