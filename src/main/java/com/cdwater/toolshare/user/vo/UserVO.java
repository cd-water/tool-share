package com.cdwater.toolshare.user.vo;

import lombok.Data;

/**
 * 用户出参（手机号/身份证脱敏展示，不含密码）
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String realName;

    /** 脱敏后手机号 */
    private String phone;

    /** 角色：0居民 1社区工作人员 2系统管理员 */
    private Integer role;
}
