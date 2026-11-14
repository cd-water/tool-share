package com.cdwater.toolshare.module.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.credit.CreditService;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalMapper;
import com.cdwater.toolshare.module.tool.ToolService;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.RentalStatus;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 工具评价：仅本人已归还的租赁单可评，一单一评、不可修改。
 * 评价行保存工具与用户快照，查询无需回表关联。
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ToolReviewMapper reviewMapper;
    private final RentalMapper rentalMapper;
    private final UserMapper userMapper;
    private final ToolService toolService;
    private final CreditService creditService;

    @Transactional
    public ReviewVO create(ReviewCreateDTO dto) {
        Long userId = StpKit.USER.getLoginIdAsLong();
        Rental rental = rentalMapper.selectById(dto.getRentalId());
        if (rental == null) {
            throw new BizException(ResultCode.NOT_FOUND, "租赁记录不存在");
        }
        if (!rental.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "只能评价本人的租赁单");
        }
        if (!RentalStatus.RETURNED.code().equals(rental.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅已归还的租赁单可以评价");
        }
        if (reviewMapper.selectCount(new LambdaQueryWrapper<ToolReview>()
                .eq(ToolReview::getRentalId, rental.getId())) > 0) {
            throw new BizException(ResultCode.CONFLICT, "该租赁单已评价，评价不可修改");
        }

        User user = userMapper.selectById(userId);
        ToolReview review = new ToolReview();
        review.setRentalId(rental.getId());
        review.setToolId(rental.getToolId());
        review.setToolName(rental.getToolName());
        review.setUserId(userId);
        review.setUserNickname(user == null || user.getNickname() == null ? "" : user.getNickname());
        review.setRating(dto.getRating());
        review.setContent(plainText(dto.getContent()));
        review.setImages(joinImages(dto.getImages()));
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发重复提交由 rental_id 唯一键兜底
            throw new BizException(ResultCode.CONFLICT, "该租赁单已评价，评价不可修改");
        }
        // 低星评价信用事件与评价同一事务；同一评价由 review_id 唯一键保证只计一次
        creditService.recordReview(userId, review.getRentalId(), review.getId(), review.getRating());
        return toVO(review);
    }

    public PageVO<ReviewVO> userPage(long page, long size) {
        return pageOf(new LambdaQueryWrapper<ToolReview>()
                .eq(ToolReview::getUserId, StpKit.USER.getLoginIdAsLong()), page, size);
    }

    public PageVO<ReviewVO> toolPage(Long toolId, long page, long size) {
        toolService.getTool(toolId);
        return pageOf(new LambdaQueryWrapper<ToolReview>().eq(ToolReview::getToolId, toolId),
                page, size);
    }

    public PageVO<ReviewVO> adminPage(Long toolId, long page, long size) {
        LambdaQueryWrapper<ToolReview> wrapper = new LambdaQueryWrapper<>();
        if (toolId != null) {
            wrapper.eq(ToolReview::getToolId, toolId);
        }
        return pageOf(wrapper, page, size);
    }

    private PageVO<ReviewVO> pageOf(LambdaQueryWrapper<ToolReview> wrapper, long page, long size) {
        Page<ToolReview> result = reviewMapper.selectPage(PageQuery.of(page, size),
                wrapper.orderByDesc(ToolReview::getCreateTime).orderByDesc(ToolReview::getId));
        return PageVO.of(result, this::toVO);
    }

    private ReviewVO toVO(ToolReview review) {
        ReviewVO vo = new ReviewVO();
        vo.setId(review.getId());
        vo.setRentalId(review.getRentalId());
        vo.setToolId(review.getToolId());
        vo.setToolName(review.getToolName());
        vo.setUserId(review.getUserId());
        vo.setUserNickname(review.getUserNickname());
        vo.setRating(review.getRating());
        vo.setContent(review.getContent());
        vo.setImages(parseImages(review.getImages()));
        vo.setCreateTime(review.getCreateTime());
        return vo;
    }

    /** 评价是纯文字：剥离 HTML 标签，不引入富文本。 */
    private String plainText(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return Jsoup.parse(content).text().trim();
    }

    private String joinImages(List<String> images) {
        if (images == null) {
            return null;
        }
        List<String> cleaned = images.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private List<String> parseImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
