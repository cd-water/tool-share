package com.cdwater.toolshare.module.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 管理端权限来源：按数据库中的当前角色计算，角色变更即时生效。
 */
@Component
@RequiredArgsConstructor
public class AdminStpInterface implements StpInterface {

    private final AdminMapper adminMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (!StpKit.ADMIN.getLoginType().equals(loginType)) {
            return List.of();
        }
        Admin admin = adminMapper.selectById(Long.valueOf(loginId.toString()));
        if (admin == null || admin.getStatus() == null || admin.getStatus() == 0) {
            return List.of();
        }
        return AdminRole.fromCode(admin.getRole())
                .map(AdminRole::permissions)
                .orElse(List.of());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }
}
