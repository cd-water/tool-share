/**
 * 管理端 Sa-Token 权限来源文件。
 * <p>实现 StpInterface，为每次鉴权提供权限码集合。
 * <h2>设计取舍</h2>
 * <p>故意不缓存权限：每次鉴权都读取 t_admin 的当前角色，
 * 因此降级或提权在下一次请求立即生效，代价是一次主键查询。
 * 管理端账号量小，这个代价可控。
 * <h2>边界</h2>
 * <ul>
 *   <li>非管理端登录类型直接返回空权限；</li>
 *   <li>账号不存在、status 为 null 或 0 时返回空权限；</li>
 *   <li>role 无法解析时返回空权限，默认拒绝。</li>
 * </ul>
 * <p>本项目不使用 Sa-Token 角色列表，getRoleList 固定返回空。
 */
package com.cdwater.toolshare.module.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
  * <p>故意不缓存权限：每次鉴权都读取 t_admin 的当前角色，保证降级/提权即时生效。
  * 非管理端登录类型或账号不存在、被禁用时返回空权限集合。
 * 管理端权限来源：按数据库中的当前角色计算，角色变更即时生效。
 */
@Component
@RequiredArgsConstructor
public class AdminStpInterface implements StpInterface {

    // 每次鉴权查库，换来确定性；管理端账号量小，代价可控
    private final AdminMapper adminMapper;

    @Override
    /**
     * 返回指定登录账号的权限码集合。
     * <p>只处理管理端登录类型；用户端登录类型直接返回空集合，
     * 防止用户 token 被误当作管理端身份使用。
     *
     * @param loginId   Sa-Token 登录 id，此处为管理员主键
     * @param loginType 登录类型，必须等于 {@code StpKit.ADMIN}
     * @return 权限码列表；账号无效时为空列表
     */
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
    /**
     * 本项目不使用 Sa-Token 的角色列表，权限全部由权限码表达。
     *
     * @param loginId   登录 id
     * @param loginType 登录类型
     * @return 固定空列表
     */
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }
}
