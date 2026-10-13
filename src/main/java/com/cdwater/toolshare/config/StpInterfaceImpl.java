package com.cdwater.toolshare.config;

import cn.dev33.satoken.stp.StpInterface;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.user.entity.User;
import com.cdwater.toolshare.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * sa-token 角色数据源：把 user.role 数值翻译为角色标识
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserMapper userMapper;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User user = userMapper.selectById(Long.parseLong(loginId.toString()));
        if (user == null) {
            return List.of();
        }
        return switch (user.getRole()) {
            case 2 -> List.of(RoleConstant.ADMIN);
            case 1 -> List.of(RoleConstant.STAFF);
            default -> List.of(RoleConstant.RESIDENT);
        };
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 只做角色控制，无独立权限点
        return List.of();
    }
}
