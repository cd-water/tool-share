/**
 * 用户视图文件。
 * <p>用于当前用户信息与个人资料展示，包含 id、脱敏手机号、昵称、头像、真实姓名与脱敏身份证。
 * <h2>约束</h2>
 * <p>phone 与 idCard 不允许出现完整值；脱敏在 UserVoMapper 的后置处理中完成，
 * 服务层不得绕过映射器直接填充这两个字段。
 * <p>调用方：UserService#info/profile/updateProfile。
 */
package com.cdwater.toolshare.module.auth;

import lombok.Data;

/**
 * 用户视图。
 * <p>手机号与身份证号在映射阶段脱敏，任何接口都不得返回完整值。
 */
@Data
public class UserVO {

    // 用户主键
    private Long id;

    // 脱敏手机号，中间四位以星号代替
    private String phone;

    // 昵称
    private String nickname;

    // 头像地址
    private String avatar;

    // 真实姓名；用于租赁单快照展示
    private String realName;

    /** 脱敏后的身份证号 */
    private String idCard;
}
