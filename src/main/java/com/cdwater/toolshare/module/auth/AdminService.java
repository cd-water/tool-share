/**
 * 管理端认证与账号管理文件。
 *
 * <h2>职责</h2>
 * <p>账号密码登录、当前账号信息、注销、账号列表与角色修改。
 *
 * <h2>安全约束</h2>
 * <p>登录失败统一提示，不区分账号不存在与密码错误；
 * 先校验密码再检查状态，避免未持有密码者探测账号是否被禁用；
 * 被禁用账号不能登录，也不能读取自身信息；
 * 角色只允许 STAFF / SUPER_ADMIN，未知编码直接拒绝。
 *
 * <h2>与权限模块的关系</h2>
 * <p>本服务只写 role 字段，接口权限由 AdminStpInterface 在每次鉴权时
 * 读取数据库当前角色计算，因此角色变更无需刷新会话即可生效。
 *
 * <h2>密码</h2>
 * <p>只保存 BCrypt 密文；BCrypt 自带随机盐，同一密码每次哈希结果不同。
 */
package com.cdwater.toolshare.module.auth;

import cn.dev33.satoken.stp.SaTokenInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.enums.AdminRole;
import com.cdwater.toolshare.module.auth.AdminLoginDTO;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.auth.AdminVO;
import com.cdwater.toolshare.module.auth.LoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端认证与账号管理服务。
 * <p>职责：账号密码登录、当前账号信息、注销、账号列表与角色修改。
 * <p>安全约束：登录失败统一提示，不区分账号不存在与密码错误；
 * 被禁用账号不能登录也不能读取自身信息；角色修改只允许合法枚举值。
 * <p>与权限模块的关系：本服务只写入 role 字段，
 * 实际接口鉴权由 {@link AdminStpInterface} 读取最新角色后完成。
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    // 管理端账号表访问
    private final AdminMapper adminMapper;
    // 实体到视图对象的映射，避免把 password 暴露给接口层
    private final AdminVoMapper adminVoMapper;
    // BCrypt 自带随机盐，同一个密码每次哈希结果不同，只能通过 matches 校验
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
      * <p>校验顺序固定：先比对账号密码，再检查账号状态；
      * 状态检查放在密码校验之后，避免未持有正确密码者探测账号是否被禁用。
      *
      * @param dto 账号与密码
      * @return 登录成功后的 token 信息
      * @throws BizException 账号密码错误（400）或账号被禁用（403）
     * 管理端登录：账号不存在 / 密码错误统一提示，不泄露账号信息
     */
    public LoginVO login(AdminLoginDTO dto) {
        // 账号唯一，按用户名精确查询
        Admin admin = adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, dto.getUsername()));
        // 账号不存在与密码错误共用同一分支和同一文案
        // 账号不存在与密码错误共用同一分支，避免账号枚举
        if (admin == null || !passwordEncoder.matches(dto.getPassword(), admin.getPassword())) {
            throw new BizException(ResultCode.BAD_REQUEST, "账号或密码错误");
        }
        if (admin.getStatus() == null || admin.getStatus() == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已禁用");
        }
        // loginId 使用管理员主键；权限在每次鉴权时按数据库角色重新计算
        StpKit.ADMIN.login(admin.getId());
        // 返回 token 名称与值，前端按约定的请求头携带
        SaTokenInfo info = StpKit.ADMIN.getTokenInfo();
        return new LoginVO(info.tokenName, info.tokenValue);
    }

    /**
     * 查询当前登录管理端账号信息。
     * <p>已被删除或禁用的账号即使持有有效 token 也拒绝返回。
     *
     * @return 当前账号视图
     * @throws BizException 账号不存在（400）或已禁用（403）
     */
    /** 当前登录管理端账号信息 */
    public AdminVO info() {
        // 以会话中的 loginId 为准，不接受请求参数指定账号
        Admin admin = adminMapper.selectById(StpKit.ADMIN.getLoginIdAsLong());
        // 账号已被删除时要求重新登录
        if (admin == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "管理端账号不存在");
        }
        if (admin.getStatus() == null || admin.getStatus() == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已禁用");
        }
        return adminVoMapper.toVO(admin);
    }

    /**
     * 注销当前管理端会话，使现有 token 立即失效。
     */
    /** 当前会话注销 */
    public void logout() {
        // 注销当前管理端会话
        StpKit.ADMIN.logout();
    }

    /**
     * 查询全部管理端账号，按 id 升序返回。
     * <p>调用方已由 {@code admin:manage} 权限保护；返回值不含密码。
     *
     * @return 管理端账号视图列表
     */
    /** 管理端账号列表（仅系统管理员） */
    public List<AdminVO> list() {
        // 按 id 升序返回，结果稳定且便于对照
        return adminMapper.selectList(new LambdaQueryWrapper<Admin>().orderByAsc(Admin::getId))
                .stream()
                .map(adminVoMapper::toVO)
                .toList();
    }

    /**
     * 修改管理端账号的角色。
     * <p>先校验角色编码，再确认账号存在，最后写库；
     * 角色修改不会注销目标账号的现有会话，但下一次鉴权即按新角色计算权限。
     *
     * @param id       目标账号 id
     * @param roleCode 目标角色编码
     * @throws BizException 角色非法（400）或账号不存在（404）
     */
    /** 修改管理端角色（仅系统管理员） */
    public void updateRole(Long id, String roleCode) {
        // 枚举解析失败直接拒绝，避免把未知字符串写入数据库
        // 角色编码必须能解析为枚举，避免未知角色进入数据库
        AdminRole role = AdminRole.fromCode(roleCode)
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST, "无效的管理端角色"));
        Admin admin = adminMapper.selectById(id);
        if (admin == null) {
            throw new BizException(ResultCode.NOT_FOUND, "管理端账号不存在");
        }
        // 写入枚举规范化的 code，保证大小写与后续解析一致
        admin.setRole(role.code());
        adminMapper.updateById(admin);
    }
}
