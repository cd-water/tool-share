package com.cdwater.toolshare.system.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.system.dto.UserItem;

/**
 * 用户管理服务（ADMIN）
 */
public interface UserService {

    PageResult<UserItem> page(long page, long size, String role, String keyword);

    void updateStatus(Long id, String status);
}
