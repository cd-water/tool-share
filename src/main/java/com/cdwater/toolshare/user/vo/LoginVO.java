package com.cdwater.toolshare.user.vo;

import lombok.Data;

/**
 * 登录出参
 */
@Data
public class LoginVO {

    /** sa-token 令牌，后续请求放 Authorization 头 */
    private String token;

    private Long userId;

    private String realName;

    /** 角色：0居民 1社区工作人员 2系统管理员 */
    private Integer role;
}
