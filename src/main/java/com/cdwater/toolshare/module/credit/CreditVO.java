package com.cdwater.toolshare.module.credit;

import com.cdwater.toolshare.shared.web.PageVO;
import lombok.Data;

/**
 * 用户信用分总览视图对象。
 *
 * <p>把“当前分数”和“最近流水”放在同一个响应中，用户打开信用分页面
 * 只需一次请求即可完成渲染。分数为 null 时按初始分 100 处理。</p>
 */
@Data
public class CreditVO {

    /** 信用分 0-100，初始 100 */
    private Integer score;

    /** 最近流水，按时间倒序分页 */
    private PageVO<CreditEventVO> events;
}
