package com.cdwater.toolshare.module.auth;

import lombok.Data;

@Data
public class ProfileUpdateDTO {

    private String nickname;

    private String avatar;

    private String realName;

    /** 留空表示不修改 */
    private String idCard;
}
