package com.cdwater.toolshare.module.auth;

import com.cdwater.toolshare.shared.validation.IdCard;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProfileUpdateDTO {

    @Size(max = 64, message = "昵称不能超过 64 个字符")
    private String nickname;

    @Size(max = 255, message = "头像地址不能超过 255 个字符")
    private String avatar;

    @Size(max = 64, message = "真实姓名不能超过 64 个字符")
    private String realName;

    /** 留空表示不修改 */
    @IdCard
    private String idCard;
}
