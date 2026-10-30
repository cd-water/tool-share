package com.cdwater.ticketbox.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginVO {

    /** 请求头名称：Authorization */
    private String tokenName;

    /** token 值，不含 Bearer 前缀 */
    private String tokenValue;
}
