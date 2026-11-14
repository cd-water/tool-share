/**
 * 登录成功响应文件。
 * <p>tokenName 为请求头名称（当前为 Authorization），
 * tokenValue 为不含 Bearer 前缀的 token 值。
 * <h2>使用约定</h2>
 * <p>前端按 tokenName 放入请求头并自行拼接前缀；
 * 服务端不返回用户角色或权限，避免把授权信息与认证响应耦合。
 * <p>调用方：用户端与管理端登录接口。
 */
package com.cdwater.toolshare.module.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录成功响应。
 * <p>tokenValue 不含 Bearer 前缀，前端按 tokenName 放入请求头时自行拼接。
 */
@Data
@AllArgsConstructor
public class LoginVO {

    /** 请求头名称：Authorization */
    private String tokenName;

    /** token 值，不含 Bearer 前缀 */
    private String tokenValue;
}
