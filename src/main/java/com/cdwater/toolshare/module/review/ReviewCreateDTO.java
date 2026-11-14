package com.cdwater.toolshare.module.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建评价的请求体。
 *
 * <p>只接收租赁单、评分、文字内容与图片地址；工具与用户信息由服务端
 * 根据租赁单和登录态填充，客户端无法伪造。评分限制为 1-5 星，内容
 * 最长 500 字，图片最多 3 张且每张地址不超过 500 字符。</p>
 *
 * <p>这里的注解只做格式校验，不校验租赁单归属与状态；这些业务校验
 * 必须放在服务层，避免绕过控制器直接调用服务时失去保护。</p>
 */
@Data
public class ReviewCreateDTO {

    /**
     * 被评价的租赁单 ID；必须是当前用户已归还的订单。
     * 服务层会再次校验归属与状态。
     */
    @NotNull(message = "租赁单不能为空")
    private Long rentalId;

    /**
     * 1-5 星评分；2 星及以下会在服务端触发信用扣分事件。
     * 评分不可修改，提交前请确认。
     */
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分不能低于 1 星")
    @Max(value = 5, message = "评分不能高于 5 星")
    private Integer rating;

    /** 评价文字，最长 500 字；服务端会剥离 HTML 标签后保存。 */
    @Size(max = 500, message = "评价内容不能超过 500 字")
    private String content;

    /** 已上传图片的地址列表，最多 3 张；保存时以逗号拼接。 */
    @Size(max = 3, message = "最多上传 3 张图片")
    private List<@Size(max = 500, message = "图片地址过长") String> images;
}
