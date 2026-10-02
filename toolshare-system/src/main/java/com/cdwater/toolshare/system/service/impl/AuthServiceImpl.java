package com.cdwater.toolshare.system.service.impl;

import com.cdwater.toolshare.system.dto.LoginRequest;
import com.cdwater.toolshare.system.dto.LoginResponse;
import com.cdwater.toolshare.system.dto.UserItem;
import com.cdwater.toolshare.system.service.AuthService;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    @Override
    public LoginResponse login(LoginRequest request) {
        // TODO 业务实现：BCrypt 校验密码 + StpUtil.login + 组装 LoginResponse
        return null;
    }

    @Override
    public void logout() {
        // TODO 业务实现：StpUtil.logout()
    }

    @Override
    public UserItem me() {
        // TODO 业务实现：按 StpUtil.getLoginIdAsLong() 查询并脱敏
        return null;
    }
}
