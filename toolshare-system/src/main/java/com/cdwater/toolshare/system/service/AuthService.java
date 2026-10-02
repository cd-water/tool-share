package com.cdwater.toolshare.system.service;

import com.cdwater.toolshare.system.dto.LoginRequest;
import com.cdwater.toolshare.system.dto.LoginResponse;
import com.cdwater.toolshare.system.dto.UserItem;

/**
 * 认证服务
 */
public interface AuthService {

    /** 登录，返回 token 与用户信息 */
    LoginResponse login(LoginRequest request);

    /** 注销当前会话 */
    void logout();

    /** 当前登录用户（敏感字段脱敏） */
    UserItem me();
}
