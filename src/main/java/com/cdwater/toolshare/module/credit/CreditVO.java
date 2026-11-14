package com.cdwater.toolshare.module.credit;

import com.cdwater.toolshare.shared.web.PageVO;
import lombok.Data;

@Data
public class CreditVO {

    /** 信用分 0-100，初始 100 */
    private Integer score;

    /** 最近流水，按时间倒序分页 */
    private PageVO<CreditEventVO> events;
}
