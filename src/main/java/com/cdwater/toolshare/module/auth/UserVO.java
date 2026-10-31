package com.cdwater.toolshare.module.auth;

import lombok.Data;

@Data
public class UserVO {

    private Long id;

    private String phone;

    private String nickname;

    private String avatar;

    private String realName;

    /** 脱敏后的身份证号 */
    private String idCard;
}
