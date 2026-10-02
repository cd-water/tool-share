package com.cdwater.toolshare.system.service.impl;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.system.dto.UserItem;
import com.cdwater.toolshare.system.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    @Override
    public PageResult<UserItem> page(long page, long size, String role, String keyword) {
        // TODO 业务实现：分页查询 + 脱敏
        return null;
    }

    @Override
    public void updateStatus(Long id, String status) {
        // TODO 业务实现：更新 status，停用时踢下线
    }
}
