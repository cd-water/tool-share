package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RentalMapper extends BaseMapper<Rental> {

    /** 报修创建/核验前锁定租赁行，避免并发重复报修或与归还竞态。 */
    @Select("SELECT * FROM t_rental WHERE id = #{id} FOR UPDATE")
    Rental selectByIdForUpdate(@Param("id") Long id);

    /**
     * 冲突查询：已生效的租赁 + 未过期的待支付租赁都占用档期。
     * 已超时但尚未被调度任务取消的待支付单不占用。
     */
    @Select("""
            SELECT * FROM t_rental
            WHERE tool_id = #{toolId}
              AND start_time < #{endTime}
              AND end_time > #{startTime}
              AND (status IN ('RESERVED', 'PICKED_UP', 'OVERDUE')
                   OR (status = 'PENDING_PAYMENT'
                       AND create_time > TIMESTAMPADD(MINUTE, -#{timeoutMinutes}, NOW())))
            """)
    List<Rental> selectConflicts(@Param("toolId") Long toolId,
                                 @Param("startTime") LocalDateTime startTime,
                                 @Param("endTime") LocalDateTime endTime,
                                 @Param("timeoutMinutes") long timeoutMinutes);

    /** 已超时但仍处于待支付的租赁单（与数据库时钟比较，避免应用与数据库时区不一致）。 */
    @Select("""
            SELECT * FROM t_rental
            WHERE status = 'PENDING_PAYMENT'
              AND create_time <= TIMESTAMPADD(MINUTE, -#{timeoutMinutes}, NOW())
            """)
    List<Rental> selectExpiredPending(@Param("timeoutMinutes") long timeoutMinutes);

    /** 支付：仅未过期、未支付的待支付单可原子转为已预约。 */
    @Update("""
            UPDATE t_rental
            SET status = 'RESERVED', deposit_status = 'PAID', update_time = NOW(), version = version + 1
            WHERE id = #{id} AND status = 'PENDING_PAYMENT' AND deposit_status = 'UNPAID'
              AND create_time > TIMESTAMPADD(MINUTE, -#{timeoutMinutes}, NOW())
            """)
    int pay(@Param("id") Long id, @Param("timeoutMinutes") long timeoutMinutes);

    /** 取消：待支付/已预约可原子取消；已支付押金同时置为已退款并记录退款金额。 */
    @Update("""
            UPDATE t_rental
            SET status = 'CANCELLED',
                cancel_reason = #{reason},
                cancel_time = NOW(),
                updated_by_name = #{updatedBy},
                refund_amount = IF(deposit_status = 'PAID', deposit_amount, refund_amount),
                deposit_status = IF(deposit_status = 'PAID', 'REFUNDED', deposit_status),
                update_time = NOW(),
                version = version + 1
            WHERE id = #{id} AND status IN ('PENDING_PAYMENT', 'RESERVED')
            """)
    int cancel(@Param("id") Long id, @Param("reason") String reason,
               @Param("updatedBy") String updatedBy);

    /** 通用条件状态流转。 */
    @Update("""
            UPDATE t_rental
            SET status = #{to}, update_time = NOW(), version = version + 1
            WHERE id = #{id} AND status = #{from}
            """)
    int transit(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    /** 逾期物化：仅使用中且未归还的单可转逾期。 */
    @Update("""
            UPDATE t_rental
            SET status = 'OVERDUE', update_time = NOW(), version = version + 1
            WHERE id = #{id} AND status = 'PICKED_UP'
            """)
    int markOverdue(@Param("id") Long id);

    /** 待支付超时：原子取消。 */
    @Update("""
            UPDATE t_rental
            SET status = 'CANCELLED', cancel_reason = '待支付超时自动取消', cancel_time = NOW(),
                update_time = NOW(), version = version + 1
            WHERE id = #{id} AND status = 'PENDING_PAYMENT'
            """)
    int expirePending(@Param("id") Long id);

    /** 归还（结算逻辑在 #12，这里只保证状态流转原子）。 */
    @Update("""
            UPDATE t_rental
            SET status = 'RETURNED',
                actual_return_time = #{actualReturnTime},
                overdue_fee = #{overdueFee},
                damage_fee = #{damageFee},
                refund_amount = #{refundAmount},
                deposit_status = 'REFUNDED',
                damage_result = #{damageResult},
                return_remark = #{remark},
                updated_by_name = #{updatedBy},
                update_time = NOW(),
                version = version + 1
            WHERE id = #{id} AND status IN ('PICKED_UP', 'OVERDUE')
            """)
    int returnRental(@Param("id") Long id,
                     @Param("actualReturnTime") LocalDateTime actualReturnTime,
                     @Param("overdueFee") BigDecimal overdueFee,
                     @Param("damageFee") BigDecimal damageFee,
                     @Param("refundAmount") BigDecimal refundAmount,
                     @Param("damageResult") String damageResult,
                     @Param("remark") String remark,
                     @Param("updatedBy") String updatedBy);
}
