/**
 * 用户个人资料更新请求体文件。
 * <p>所有字段可选，语义按字段区分：
 * <ul>
 *   <li>nickname：空白串视为不修改；</li>
 *   <li>avatar：null 不修改，空串表示清空；</li>
 *   <li>realName：null 不修改；</li>
 *   <li>idCard：留空不修改，非空时必须通过 IdCard 校验。</li>
 * </ul>
 * <p>身份证由服务层加密后落库，接口从不回显完整值。
 * <p>调用方：UserProfileController#update。
 */
package com.cdwater.toolshare.module.auth;

import com.cdwater.toolshare.shared.validation.IdCard;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户个人资料更新请求。
 * <p>所有字段可选：昵称、头像、真实姓名按 null 判断是否修改；
 * 身份证号留空表示不修改，传入时必须是合法身份证格式。
 */
@Data
public class ProfileUpdateDTO {

    @Size(max = 64, message = "昵称不能超过 64 个字符")
    // 展示用昵称；传入空白串时服务层视作无有效修改
    private String nickname;

    @Size(max = 255, message = "头像地址不能超过 255 个字符")
    // 头像地址；传入 null 不修改，传入空串表示清空
    private String avatar;

    @Size(max = 64, message = "真实姓名不能超过 64 个字符")
    // 真实姓名；预约前必须已完善，用于租赁单快照
    private String realName;

    /** 留空表示不修改 */
    @IdCard
    private String idCard;
}
