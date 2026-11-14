/**
 * 租赁单 SQL 映射文件。
 *
 * <h2>并发约定</h2>
 * <p>本文件中的所有状态变更都使用“带原状态条件的 UPDATE”。
 * 影响行数为 0 是正常的并发结果，调用方据此返回 409，而不是先查后改。
 *
 * <h2>时间约定</h2>
 * <p>超时与逾期判断尽量使用数据库 NOW()，
 * 避免应用服务器与数据库时钟/时区不一致导致边界行为漂移。
 *
 * <h2>区间语义</h2>
 * <p>租期冲突使用半开区间：start_time < 目标结束 且 end_time > 目标开始。
 * 端点相接不算重叠，与业务上“上一单结束即可下一单开始”一致。
 *
  * <h2>方法语义</h2>
  * <ul>
  *   <li>selectByIdForUpdate：报修/核验前锁租赁行，串行化同一单据的跨表流程；</li>
  *   <li>selectConflicts：区间冲突查询，待支付单按超时窗口决定是否占用；</li>
  *   <li>selectExpiredPending：按数据库时钟筛出超时待支付单；</li>
  *   <li>pay：待支付 + 未支付 + 未超时 -> 已预约 + 已支付；</li>
  *   <li>cancel：待支付/已预约 -> 已取消，同时按押金状态计算退款字段；</li>
  *   <li>transit：通用 from -> to 原子流转；</li>
  *   <li>markOverdue：使用中 -> 逾期；</li>
  *   <li>expirePending：待支付 -> 已取消，写入系统取消原因；</li>
  *   <li>returnRental：使用中/逾期 -> 已归还，一次性写入结算金额。</li>
  * </ul>
  *
  * <h2>索引与性能</h2>
  * <p>冲突查询依赖 (tool_id, start_time, end_time, status) 组合索引；
  * 待支付超时查询依赖 (status, create_time)。
  * 所有状态更新都按主键定位，避免范围锁。
  *
  * <h2>锁顺序</h2>
  * <p>预约创建先锁工具行再插入租赁单；报修/核验锁租赁行。
  * 代码中不要反向获取锁，避免死锁。
  *
       * <h2>测试建议</h2>
       * <p>用并发测试覆盖支付/取消/归还的条件更新；
       * 用边界用例覆盖待支付超时与区间端点相接。
      * <h2>指标建议</h2>
      * <p>可统计各条件更新返回 0 的比例：持续偏高说明并发或超时配置异常；
      * 支付/取消/归还的 0 行比例突然变化，通常对应前端重复提交或调度延迟。
     * <h2>索引建议</h2>
     * <p>(tool_id, start_time, end_time, status)：支撑冲突查询；
     * (status, create_time)：支撑待支付超时扫描；
     * (user_id, create_time)：支撑用户端分页；
     * (status, end_time)：支撑逾期物化。
     * 上线前用 EXPLAIN 确认条件更新走主键、范围查询走组合索引。
    * <h2>常见故障</h2>
    * <ul>
    *   <li>支付/取消/归还影响 0 行：多为并发流转或已超时，按 409 处理；</li>
    *   <li>冲突查询漏单：确认隔离级别为 READ_COMMITTED 且工具行锁已生效；</li>
    *   <li>超时边界不一致：确认应用与数据库时区一致，且取消任务使用同一配置；</li>
    *   <li>死锁：保持“先工具后租赁”的锁顺序，不要反向获取。</li>
    * </ul>
   * <h2>调用约定</h2>
   * <p>所有状态更新返回 int：1 表示成功，0 表示条件不满足。
   * 调用方不应把 0 当作异常，而应转换为业务冲突（409）。
   * <p>selectConflicts 的 timeoutMinutes 必须与取消任务使用同一配置，
   * 否则会出现“占用判定与超时取消”边界不一致。
   * <p>selectByIdForUpdate 只允许在已有事务中使用；
   * 非事务调用会立即释放锁，失去串行化意义。
   *
   * <h2>SQL 文本块维护</h2>
   * <p>状态编码以字符串字面量写入 SQL，修改枚举 code 时必须同步本文件；
   * 建议通过集成测试覆盖各条件更新的成功与失败路径。
  * <h2>时区</h2>
  * <p>TIMESTAMPADD/NOW() 使用数据库会话时区；
  * 应用侧展示与业务判断使用 JVM 时区，部署时应保持一致。
 * <h2>锁</h2>
 * <p>selectByIdForUpdate 只在报修/核验等需要跨表一致性的事务内使用；
 * 预约创建锁的是工具行（ToolMapper），不是租赁单。
 */
package com.cdwater.toolshare.module.rental;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 租赁单数据访问层。
 * <p>所有状态流转都使用“带原状态条件的 UPDATE”而不是先查后改：
 * 影响行数为 0 即表示状态已被并发改变，调用方据此返回冲突。
 * 时间比较尽量使用数据库 NOW()，避免应用与数据库时钟/时区不一致。
 */
@Mapper
public interface RentalMapper extends BaseMapper<Rental> {

    // 必须在事务内调用；锁持有到事务结束，期间同一租赁单的报修/归还流程串行化。
    /** 报修创建/核验前锁定租赁行，避免并发重复报修或与归还竞态。 */
    @Select("SELECT * FROM t_rental WHERE id = #{id} FOR UPDATE")
    // 行锁查询，必须在事务内调用
    Rental selectByIdForUpdate(@Param("id") Long id);

    /**
      * <p>区间重叠采用半开语义：start_time < 目标结束 且 end_time > 目标开始。
      * 未过期的待支付单也占用档期，防止同一时段被重复下单；
      * 已超时但尚未被调度任务取消的待支付单不再占用，避免误伤用户。
      *
      * @param toolId         工具 id
      * @param startTime      目标开始时间
      * @param endTime        目标结束时间
      * @param timeoutMinutes 待支付占用窗口（分钟）
      * @return 与目标区间重叠的租赁单
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
    // 返回与目标区间重叠的租赁单
    List<Rental> selectConflicts(@Param("toolId") Long toolId,
                                 @Param("startTime") LocalDateTime startTime,
                                 @Param("endTime") LocalDateTime endTime,
                                 @Param("timeoutMinutes") long timeoutMinutes);

    // 查询与取消更新使用完全相同的超时条件，保证边界判断一致。
    /** 已超时但仍处于待支付的租赁单（与数据库时钟比较，避免应用与数据库时区不一致）。 */
    @Select("""
            SELECT * FROM t_rental
            WHERE status = 'PENDING_PAYMENT'
              AND create_time <= TIMESTAMPADD(MINUTE, -#{timeoutMinutes}, NOW())
            """)
    // 返回满足超时条件的待支付单，调用方逐单原子取消
    // 返回超时待支付单，调用方逐单原子取消
    List<Rental> selectExpiredPending(@Param("timeoutMinutes") long timeoutMinutes);

    // 状态、押金状态与创建时间三重条件同时满足才更新；
    // 返回 0 表示已超时、已支付或状态已变化，由服务层转为 409。
    /** 支付：仅未过期、未支付的待支付单可原子转为已预约。 */
    @Update("""
            UPDATE t_rental
            SET status = 'RESERVED', deposit_status = 'PAID', update_time = NOW(), version = version + 1
            WHERE id = #{id} AND status = 'PENDING_PAYMENT' AND deposit_status = 'UNPAID'
              AND create_time > TIMESTAMPADD(MINUTE, -#{timeoutMinutes}, NOW())
            """)
    // 返回 1 表示支付成功；0 表示状态/押金状态不符或已超时
    // 返回 1 表示成功；0 表示已超时/已支付/状态已变化
    // 1=成功，0=状态/押金/超时条件不满足
    int pay(@Param("id") Long id, @Param("timeoutMinutes") long timeoutMinutes);

    // 退款字段用 IF 表达式按更新时的押金状态计算，保证并发支付时也不会漏退款。
    // 已取消、已取件、已归还的单不满足 WHERE 条件，取消操作影响 0 行。
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
    // 返回 1 表示取消成功；0 表示已取件/已归还/已取消
    // 返回 1 表示成功；0 表示已取件/已归还/已取消
    // 1=成功，0=当前状态不可取消
    int cancel(@Param("id") Long id, @Param("reason") String reason,
               @Param("updatedBy") String updatedBy);

    // 仅允许 from -> to 的直线迁移；调用前服务层已用状态机校验。
    /** 通用条件状态流转。 */
    @Update("""
            UPDATE t_rental
            SET status = #{to}, update_time = NOW(), version = version + 1
            WHERE id = #{id} AND status = #{from}
            """)
    // 返回 1 表示迁移成功；0 表示当前状态不是 from
    // 返回 1 表示成功；0 表示当前状态不是 from
    // 1=成功，0=当前状态不是 from
    int transit(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    // 只处理 PICKED_UP；已归还/已取消的单即使时间已过也不会被改写。
    /** 逾期物化：仅使用中且未归还的单可转逾期。 */
    @Update("""
            UPDATE t_rental
            SET status = 'OVERDUE', update_time = NOW(), version = version + 1
            WHERE id = #{id} AND status = 'PICKED_UP'
            """)
    // 返回 1 表示物化成功；0 表示已被归还或并发流转
    // 返回 1 表示物化成功；0 表示已归还或并发流转
    // 1=成功，0=已被归还或并发流转
    int markOverdue(@Param("id") Long id);

    // 取消原因固定为系统文案，便于区分用户取消与超时取消。
    /** 待支付超时：原子取消。 */
    @Update("""
            UPDATE t_rental
            SET status = 'CANCELLED', cancel_reason = '待支付超时自动取消', cancel_time = NOW(),
                update_time = NOW(), version = version + 1
            WHERE id = #{id} AND status = 'PENDING_PAYMENT'
            """)
    // 返回 1 表示超时取消成功；0 表示已支付或已取消
    // 返回 1 表示超时取消成功；0 表示已支付或已取消
    // 1=成功，0=已支付或已取消
    int expirePending(@Param("id") Long id);

    // 归还金额由服务层计算后传入；本 SQL 只保证状态与金额一次性原子写入。
    // 只有使用中/逾期状态可以归还，重复提交影响 0 行。
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
    // 返回 1 表示归还成功；0 表示状态不允许或已被并发归还
    // 返回 1 表示归还成功；0 表示状态不允许或已归还
    // 1=成功，0=状态不允许或已归还
    int returnRental(@Param("id") Long id,
                     @Param("actualReturnTime") LocalDateTime actualReturnTime,
                     @Param("overdueFee") BigDecimal overdueFee,
                     @Param("damageFee") BigDecimal damageFee,
                     @Param("refundAmount") BigDecimal refundAmount,
                     @Param("damageResult") String damageResult,
                     @Param("remark") String remark,
                     @Param("updatedBy") String updatedBy);
}
