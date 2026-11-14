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
 *
 * <p>创建评价时会依次校验：租赁单存在、归属当前用户、状态为已归还、
 * 尚未评价。任一条件不满足都抛出业务异常并返回语义化错误码。通过
 * 校验后把工具名与用户昵称写入评价行，形成历史快照。</p>
 *
 * <p>低星评价（2 星及以下）会在同一事务内调用信用服务扣分。评价插入
 * 与信用流水都依赖数据库唯一键保证幂等：rental_id 防止一单多评，
 * review_id 防止同一评价重复扣分。若信用写入失败，整个事务回滚，
 * 不会留下“有评价但没扣分”的不一致状态。</p>
 *
 * <p>评价内容是纯文本：服务端使用 Jsoup 剥离 HTML 标签，不引入富文本。
 * 图片只保存已上传文件的地址，以逗号分隔。查询时再拆分为数组返回。</p>
 *
 * <p>为什么保存快照：工具可能改名或下架，用户可能改昵称；历史评价应
 * 保持提交时的语境，否则旧评价会与当时看到的工具名不一致。</p>
 *
 * <p>为什么不可修改：评价影响工具排序与信用分，允许修改会破坏历史
 * 一致性。若用户误评，只能由业务另行处理，本服务不提供更新入口。</p>
 *
 * <p>并发与事务：create 方法带事务注解，评价插入与信用扣分同生共死。
 * 两个唯一键分别负责“一单一评”和“一评一扣分”，即使上层重试也不会
 * 产生重复数据。</p>
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    /** 评价表访问入口，负责插入、分页与唯一键冲突检测。 */
    private final ToolReviewMapper reviewMapper;

    /** 租赁表访问入口，用于校验评价资格与读取工具快照来源。 */
    private final RentalMapper rentalMapper;

    /** 用户表访问入口，用于读取昵称快照。 */
    private final UserMapper userMapper;

    /** 工具服务，用于在查看工具评价前确认工具存在。 */
    private final ToolService toolService;

    /** 信用服务，低星评价时写入扣分事件。 */
    private final CreditService creditService;

    /**
     * 提交一条评价。
     *
     * <p>业务流程：校验租赁单归属与状态 → 幂等预检 → 组装快照 →
     * 插入评价 → 触发低星信用事件。预检与唯一键双保险：预检提供友好
     * 错误提示，唯一键负责兜底并发提交。</p>
     *
     * @param dto 评价请求体
     * @return 新建评价的视图对象
     * @throws BizException 租赁单不存在、不属于当前用户、状态不是已归还，
     *                      或该租赁单已经评价过
     */
    @Transactional
    public ReviewVO create(ReviewCreateDTO dto) {
        // 评价人始终取自登录会话，忽略请求体中可能存在的用户信息。
        Long userId = StpKit.USER.getLoginIdAsLong();
        Rental rental = rentalMapper.selectById(dto.getRentalId());
        if (rental == null) {
            // 不区分“不存在”和“无权访问”，避免泄露他人订单是否存在。
            throw new BizException(ResultCode.NOT_FOUND, "租赁单不存在");
        }
        if (!rental.getUserId().equals(userId)) {
            // 只能评价自己的订单，防止刷评或恶意评价他人。
            throw new BizException(ResultCode.FORBIDDEN, "只能评价本人的租赁单");
        }
        if (!RentalStatus.RETURNED.code().equals(rental.getStatus())) {
            // 未归还的订单可能仍在使用或产生费用，不允许提前评价。
            throw new BizException(ResultCode.CONFLICT, "仅已归还的租赁单可以评价");
        }
        if (reviewMapper.selectCount(new LambdaQueryWrapper<ToolReview>()
                .eq(ToolReview::getRentalId, rental.getId())) > 0) {
            // 预检给出明确提示；真正的并发防重仍依赖租约唯一键。
            // 预检与插入之间仍有并发窗口，因此不能只靠这一次查询。
            throw new BizException(ResultCode.CONFLICT, "该租赁单已评价，评价不可修改");
        }

        User user = userMapper.selectById(userId);
        ToolReview review = new ToolReview();
        // 以下字段均为落库快照，后续工具改名或用户改昵称不影响历史评价。
        review.setRentalId(rental.getId());
        review.setToolId(rental.getToolId());
        review.setToolName(rental.getToolName());
        review.setUserId(userId);
        review.setUserNickname(user == null || user.getNickname() == null ? "" : user.getNickname());
        // 评分已由 DTO 注解限制在 1-5，此处直接保存。
        review.setRating(dto.getRating());
        // 评价内容只保留纯文本，杜绝存储型 XSS。
        review.setContent(plainText(dto.getContent()));
        // 图片以逗号拼接，空列表归一为 null，避免存储空字符串。
        review.setImages(joinImages(dto.getImages()));
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发重复提交由 rental_id 唯一键兜底
            throw new BizException(ResultCode.CONFLICT, "该租赁单已评价，评价不可修改");
        }
        // 低星评价信用事件与评价同一事务；同一评价由 review_id 唯一键保证只计一次
        // 若信用写入抛出异常，评价插入会随事务一起回滚。
        creditService.recordReview(userId, review.getRentalId(), review.getId(), review.getRating());
        return toVO(review);
    }

    /**
     * 分页查询当前用户提交的评价。
     *
     * <p>结果按创建时间与主键倒序，保证同一时间提交的评价也有稳定顺序。
     * 用户维度来自会话，不接受外部参数。</p>
     *
     * @param page 页码，由 PageQuery 收敛
     * @param size 每页条数，由 PageQuery 收敛
     * @return 当前用户评价分页
     */
    public PageVO<ReviewVO> userPage(long page, long size) {
        // 用户 ID 来自会话，保证“我的评价”只包含本人数据。
        return pageOf(new LambdaQueryWrapper<ToolReview>()
                .eq(ToolReview::getUserId, StpKit.USER.getLoginIdAsLong()), page, size);
    }

    /**
     * 分页查询某个工具的公开评价。
     *
     * <p>先调用工具服务确认工具存在：不存在的工具应返回 404，而不是
     * 返回空分页，便于前端区分“无评价”和“工具已删除”。</p>
     *
     * @param toolId 工具 ID
     * @param page   页码
     * @param size   每页条数
     * @return 该工具的评价分页
     * @throws BizException 工具不存在时抛出
     */
    public PageVO<ReviewVO> toolPage(Long toolId, long page, long size) {
        // 不存在时由工具服务抛出 404，而不是返回空分页。
        toolService.getTool(toolId);
        return pageOf(new LambdaQueryWrapper<ToolReview>().eq(ToolReview::getToolId, toolId),
                page, size);
    }

    /**
     * 管理端评价分页，可选按工具过滤。
     *
     * <p>管理端只读，不提供修改与删除。toolId 为空时查询全部工具，
     * 便于运营总览评价数据。</p>
     *
     * @param toolId 可选工具 ID
     * @param page   页码
     * @param size   每页条数
     * @return 评价分页
     */
    public PageVO<ReviewVO> adminPage(Long toolId, long page, long size) {
        LambdaQueryWrapper<ToolReview> wrapper = new LambdaQueryWrapper<>();
        if (toolId != null) {
            // 只有显式传入工具 ID 时才追加过滤条件。
            wrapper.eq(ToolReview::getToolId, toolId);
        }
        return pageOf(wrapper, page, size);
    }

    /**
     * 统一分页查询与视图转换。
     *
     * <p>所有对外分页入口共用同一排序规则：创建时间倒序、主键倒序。
     * 这样即使用户评价与工具评价的过滤条件不同，展示顺序也保持一致。</p>
     *
     * @param wrapper 已组装好过滤条件的查询包装器
     * @param page    页码
     * @param size    每页条数
     * @return 评价分页视图
     */
    private PageVO<ReviewVO> pageOf(LambdaQueryWrapper<ToolReview> wrapper, long page, long size) {
        // 分页参数经 PageQuery 校正，非法页码/条数不会传到 SQL。
        Page<ToolReview> result = reviewMapper.selectPage(PageQuery.of(page, size),
                wrapper.orderByDesc(ToolReview::getCreateTime).orderByDesc(ToolReview::getId));
        // 主键倒序是时间倒序的稳定补充，避免同一秒内的评价顺序抖动。
        return PageVO.of(result, this::toVO);
    }

    /**
     * 把评价实体转换为视图对象。
     *
     * <p>图片字段需要从逗号分隔字符串还原为数组；其余字段直接搬运。
     * 转换过程不访问数据库，可安全用于分页映射。</p>
     *
     * @param review 评价实体
     * @return 评价视图
     */
    private ReviewVO toVO(ToolReview review) {
        ReviewVO vo = new ReviewVO();
        // 主键与关联单据用于前端跳转与列表 key。
        vo.setId(review.getId());
        vo.setRentalId(review.getRentalId());
        vo.setToolId(review.getToolId());
        // 工具名与昵称直接使用快照，不回查工具表/用户表。
        vo.setToolName(review.getToolName());
        vo.setUserId(review.getUserId());
        vo.setUserNickname(review.getUserNickname());
        vo.setRating(review.getRating());
        vo.setContent(review.getContent());
        // 无图时返回空列表，前端可直接遍历，无需判空。
        // 图片地址为上传接口返回的直链或代理地址，前端按原样渲染。
        vo.setImages(parseImages(review.getImages()));
        vo.setCreateTime(review.getCreateTime());
        return vo;
    }

    /** 评价是纯文字：剥离 HTML 标签，不引入富文本。 */
    private String plainText(String content) {
        if (!StringUtils.hasText(content)) {
            // 空内容归一为空串，避免数据库出现 null 与空串两种形态。
            // 允许用户只评分不写文字。
            return "";
        }
        // Jsoup.parse(...).text() 只保留文本节点，script/style 内容也会被丢弃。
        return Jsoup.parse(content).text().trim();
    }

    /**
     * 清洗并拼接图片地址。
     *
     * <p>过滤空白项并去除首尾空格；全部为空时返回 null，使数据库只保存
     * 真正有图的评价。地址本身来自上传接口，服务端不再校验其可达性。</p>
     *
     * @param images 图片地址列表，允许为 null
     * @return 逗号分隔的图片地址；无有效地址时返回 null
     */
    private String joinImages(List<String> images) {
        if (images == null) {
            return null;
        }
        // 先去空白再拼接，避免数据库中出现 ",," 或前后空格。
        List<String> cleaned = images.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        // 全部为空时存 null，查询侧统一转换为空列表。
        // 逗号是约定的分隔符，图片地址本身不应包含逗号。
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    /**
     * 把逗号分隔的图片字段拆回地址列表。
     *
     * <p>历史数据可能包含空项或多余空格，这里统一清洗。返回值恒为非 null
     * 列表，调用方无需再做空值判断。</p>
     *
     * @param images 逗号分隔的图片地址，允许为 null 或空白
     * @return 清洗后的图片地址列表；无有效地址时返回空列表
     */
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
