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
 */
@Service
@RequiredArgsConstructor
public class CreditService {

    public static final int INITIAL_SCORE = CreditPolicy.INITIAL_SCORE;

    private final UserCreditEventMapper eventMapper;
    private final UserMapper userMapper;

    /** 归还验收：按时/逾期与严重损坏为独立事件，同一租赁各只记一次。 */
    public void recordReturn(Long userId, Long rentalId, String orderNo,
                             boolean onTime, boolean severeDamage) {
        if (onTime) {
            record(userId, CreditEventType.ON_TIME_RETURN, rentalId, null,
                    "租赁单 " + orderNo + " 按时归还");
        } else {
            record(userId, CreditEventType.OVERDUE, rentalId, null,
                    "租赁单 " + orderNo + " 逾期归还");
        }
        if (severeDamage) {
            record(userId, CreditEventType.SEVERE_DAMAGE, rentalId, null,
                    "租赁单 " + orderNo + " 验收为严重损坏");
        }
    }

    /** 收到 2 星及以下评价：同一评价只记一次。 */
    public void recordReview(Long userId, Long rentalId, Long reviewId, int rating) {
        if (rating > 2) {
            return;
        }
        record(userId, CreditEventType.LOW_REVIEW, rentalId, reviewId,
                "租赁单评价 " + rating + " 星");
    }

    /** 当前登录用户的分数与最近流水。 */
    public CreditVO overview(long page, long size) {
        Long userId = StpKit.USER.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        Page<UserCreditEvent> events = eventMapper.selectPage(PageQuery.of(page, size),
                new LambdaQueryWrapper<UserCreditEvent>()
                        .eq(UserCreditEvent::getUserId, userId)
                        .orderByDesc(UserCreditEvent::getCreateTime)
                        .orderByDesc(UserCreditEvent::getId));
        CreditVO vo = new CreditVO();
        vo.setScore(user.getCreditScore() == null ? INITIAL_SCORE : user.getCreditScore());
        vo.setEvents(PageVO.of(events, this::toVO));
        return vo;
    }

    /**
     * 锁用户行后按当前分数计算实际变化并落流水；0-100 裁剪只体现在实际 delta。
     * 唯一键冲突表示同一单据/评价已计过分，直接忽略，避免重复加减。
     */
    private void record(Long userId, CreditEventType type, Long rentalId, Long reviewId,
                        String reason) {
        User user = userMapper.selectByIdForUpdate(userId);
        if (user == null) {
            return;
        }
        int current = user.getCreditScore() == null ? INITIAL_SCORE : user.getCreditScore();
        int target = CreditPolicy.apply(current, type);
        UserCreditEvent event = new UserCreditEvent();
        event.setUserId(userId);
        event.setEventType(type.code());
        event.setDelta(target - current);
        event.setRentalId(rentalId);
        event.setReviewId(reviewId);
        event.setReason(reason);
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException e) {
            return;
        }
        if (target != current) {
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getCreditScore, target));
        }
    }

    private CreditEventVO toVO(UserCreditEvent event) {
        CreditEventVO vo = new CreditEventVO();
        vo.setId(event.getId());
        vo.setEventType(event.getEventType());
        vo.setEventLabel(CreditEventType.fromCode(event.getEventType())
                .map(CreditEventType::label).orElse(event.getEventType()));
        vo.setDelta(event.getDelta());
        vo.setRentalId(event.getRentalId());
        vo.setReviewId(event.getReviewId());
        vo.setReason(event.getReason());
        vo.setCreateTime(event.getCreateTime());
        return vo;
    }
}
