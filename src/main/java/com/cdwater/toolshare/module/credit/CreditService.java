package com.cdwater.toolshare.module.credit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.CreditEventType;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 信用分：初始 100，区间 0-100。每次规则事件写一条流水，
 * 分数按流水中记录的实际 delta 增量更新，二者始终一致。
 *
 * <p>写入流程固定为：锁用户行 → 读取当前分 → 用 {@link CreditPolicy}
 * 计算目标分 → 先插流水（依靠唯一键去重） → 再更新用户分数。先插流水
 * 是为了让唯一键在并发下成为“是否已计分”的判定点；只有插入成功才更新
 * 分数，避免重复事件造成重复加减。</p>
 *
 * <p>事件入口由归还验收与低星评价两个业务流程调用。调用方无需保证幂等：
 * 同一租赁单的同类型事件、同一评价的低星事件都会在数据库唯一键层面被
 * 拦截。加锁顺序固定为“用户行锁”，不会与租赁/工具行锁形成交叉等待。</p>
 *
 * <p>本服务不负责发送通知，也不主动刷新缓存；分数变更在事务内完成，
 * 读取方以数据库为准。若后续引入信用分缓存，应在事务提交后刷新，
 * 可复用 {@code AfterCommit}。</p>
 *
 * <p>异常策略：用户不存在于读接口时抛出未授权；写入口遇到已删除用户
 * 则静默跳过，因为此时业务单据也已失效。数据库唯一键冲突不是错误，
 * 而是幂等路径的一部分，因此不记录告警日志。</p>
 *
 * <p>线程安全：本类无可变实例状态，Spring 单例可被并发调用；真正的
 * 互斥依赖数据库行锁与唯一键，而不是 JVM 锁。多实例部署时该假设仍然
 * 成立，无需额外分布式锁。</p>
 *
 * <p>流水与分数的可审计性：流水只追加不修改，分数是流水的物化结果。
 * 若出现不一致，可按用户重算流水 delta 之和进行对账。</p>
 */
@Service
@RequiredArgsConstructor
public class CreditService {

    /** 对外暴露的初始分常量，语义与 {@link CreditPolicy#INITIAL_SCORE} 一致。 */
    public static final int INITIAL_SCORE = CreditPolicy.INITIAL_SCORE;

    /**
     * 信用分流水 Mapper，负责流水的插入与分页查询。
     * 唯一键冲突会以 {@link DuplicateKeyException} 形式抛出，由本服务捕获。
     */
    private final UserCreditEventMapper eventMapper;

    /**
     * 用户 Mapper，负责加行锁读取分数并回写目标分。
     * 使用 {@code selectByIdForUpdate} 而非普通查询，确保并发事件串行化。
     */
    private final UserMapper userMapper;

    // 归还验收入口：一次调用可能写入两条流水（基础事件 + 严重损坏）。
    // userId/rentalId 必须来自服务端已校验的租赁单，不能使用前端参数；
    // orderNo 仅用于生成可读原因，不参与任何唯一性判断。
    // 本方法不校验租赁单状态，调用方需确保验收已完成。
    /** 归还验收：按时/逾期与严重损坏为独立事件，同一租赁各只记一次。 */
    public void recordReturn(Long userId, Long rentalId, String orderNo,
                             boolean onTime, boolean severeDamage) {
        // 按时与逾期互斥，保证一次归还只产生其中一个基础事件。
        // orderNo 只用于拼接用户可读的流水原因，不参与唯一键判断。
        if (onTime) {
            record(userId, CreditEventType.ON_TIME_RETURN, rentalId, null,
                    "租赁单 " + orderNo + " 按时归还");
        } else {
            record(userId, CreditEventType.OVERDUE, rentalId, null,
                    "租赁单 " + orderNo + " 逾期归还");
        }
        // 严重损坏是独立叠加事件：逾期归还可同时触发损坏扣分。
        // 由于事件类型不同，两次 record 各自拥有独立唯一键，不会互相覆盖。
        if (severeDamage) {
            record(userId, CreditEventType.SEVERE_DAMAGE, rentalId, null,
                    "租赁单 " + orderNo + " 验收为严重损坏");
        }
    }

    // 评价入口：rating 由调用方从已落库的评价行读取，避免前端篡改星级。
    // 同一评价重复触发时依赖 review_id 唯一键去重，方法本身保持幂等。
    // 评价与信用事件通常处于同一事务，评价回滚时流水也会回滚。
    /** 收到 2 星及以下评价：同一评价只记一次。 */
    public void recordReview(Long userId, Long rentalId, Long reviewId, int rating) {
        // 3 星及以上属于可接受评价，不触发信用事件。
        if (rating > 2) {
            return;
        }
        // reviewId 参与唯一键：同一评价无论被重复提交多少次，只扣一次分。
        record(userId, CreditEventType.LOW_REVIEW, rentalId, reviewId,
                "租赁单评价 " + rating + " 星");
    }

    // 只读入口：始终查询当前登录用户，不接受 userId 参数。
    // page/size 会由 PageQuery 收敛到合法区间，非法值不会导致 SQL 异常。
    // 返回的分数按数据库当前值展示，流水按创建时间倒序。
    // 用户不存在时按未授权处理，避免泄露“用户是否曾存在”。
    /** 当前登录用户的分数与最近流水。 */
    public CreditVO overview(long page, long size) {
        // 用户 ID 取自登录会话，调用方无法指定他人。
        Long userId = StpKit.USER.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 会话仍有效但用户记录已被删除，视为未登录，避免返回空壳数据。
            // 这里不返回 404，以免暴露账号是否曾经存在。
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        // 流水按创建时间与主键双列倒序，时间相同时仍能稳定分页。
        // 仅查询当前用户，避免依赖前端传参造成越权。
        Page<UserCreditEvent> events = eventMapper.selectPage(PageQuery.of(page, size),
                new LambdaQueryWrapper<UserCreditEvent>()
                        .eq(UserCreditEvent::getUserId, userId)
                        .orderByDesc(UserCreditEvent::getCreateTime)
                        .orderByDesc(UserCreditEvent::getId));
        CreditVO vo = new CreditVO();
        // 历史用户可能没有分数字段，按初始分兜底展示。
        // 这里不回写数据库，读取路径保持只读，避免读请求产生额外写锁。
        // 分数为 null 只影响展示，不会触发任何信用事件。
        vo.setScore(user.getCreditScore() == null ? INITIAL_SCORE : user.getCreditScore());
        // 分页对象转换为前端契约，实体到 VO 的翻译集中在 toVO。
        vo.setEvents(PageVO.of(events, this::toVO));
        return vo;
    }

    // 私有入账核心：所有公开事件入口最终都汇聚到这里。
    // 使用用户行锁串行化同一用户的并发事件；不同用户之间互不阻塞。
    // 先插流水再更新分数，使唯一键成为幂等判定点。
    // 若流水插入成功但后续更新失败，事务会一起回滚，不会留下不一致数据。
    // 流水原因直接面向用户展示，调用方应写入可读文案。
    /**
     * 锁用户行后按当前分数计算实际变化并落流水；0-100 裁剪只体现在实际 delta。
     * 唯一键冲突表示同一单据/评价已计过分，直接忽略，避免重复加减。
     */
    private void record(Long userId, CreditEventType type, Long rentalId, Long reviewId,
                        String reason) {
        // 行锁保证同一用户并发事件按串行方式计算，避免丢失更新。
        // 注意：锁必须在读取当前分之前获取，否则两个事务会读到同一个旧值。
        User user = userMapper.selectByIdForUpdate(userId);
        if (user == null) {
            // 用户已被删除时不制造孤儿流水，静默跳过。
            return;
        }
        int current = user.getCreditScore() == null ? INITIAL_SCORE : user.getCreditScore();
        int target = CreditPolicy.apply(current, type);
        // current 与 target 都处于 0-100，因此 delta 的绝对值不会超过 100。
        // 到边界时 delta 可能为 0，此时仍写流水以记录事件已发生。
        UserCreditEvent event = new UserCreditEvent();
        event.setUserId(userId);
        event.setEventType(type.code());
        // delta 记录实际变化而非规则建议值，保证流水求和等于当前分数。
        // 例如 99 分按时归还的规则值是 +2，但实际 delta 只有 +1。
        event.setDelta(target - current);
        event.setRentalId(rentalId);
        event.setReviewId(reviewId);
        event.setReason(reason);
        // createTime 由数据库默认值填充，这里不显式设置，避免时钟不一致。
        try {
            // 先落流水：唯一键在这里充当幂等闸门。
            eventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            // 同一单据/评价已有流水：事件已入账，直接返回即可。
            // 不更新分数，从而保证重复请求不会重复加减。
            return;
        }
        if (target != current) {
            // 只有目标分与当前分不同才回写，减少无意义的行更新。
            // 当前事务已持有用户行锁，此处更新不会与其它信用事件竞争。
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getCreditScore, target));
        }
    }

    /**
     * 把流水实体转换为视图对象，并把事件代码翻译为中文名称。
     *
     * <p>转换只做字段搬运与代码翻译，不访问数据库，因此可以安全地作为
     * 分页映射函数使用。未知事件代码会原样返回，保证历史数据可展示。</p>
     *
     * @param event 已持久化的流水实体
     * @return 供用户端展示的流水视图
     */
    private CreditEventVO toVO(UserCreditEvent event) {
        CreditEventVO vo = new CreditEventVO();
        // 主键与关联单据原样透出，供前端跳转到对应租赁单或评价。
        vo.setId(event.getId());
        vo.setEventType(event.getEventType());
        // 未知代码保留原值，避免历史数据导致页面出现 null。
        // 中文名称只用于展示，事件判断仍以 eventType 为准。
        vo.setEventLabel(CreditEventType.fromCode(event.getEventType())
                .map(CreditEventType::label).orElse(event.getEventType()));
        // delta 是入账时的实际变化，直接展示即可，不需要再次套用规则。
        vo.setDelta(event.getDelta());
        vo.setRentalId(event.getRentalId());
        vo.setReviewId(event.getReviewId());
        vo.setReason(event.getReason());
        vo.setCreateTime(event.getCreateTime());
        return vo;
    }
}
