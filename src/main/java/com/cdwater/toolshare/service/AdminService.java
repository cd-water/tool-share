package com.cdwater.toolshare.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.common.BizException;
import com.cdwater.toolshare.common.ResultCode;
import com.cdwater.toolshare.config.StpKit;
import com.cdwater.toolshare.dto.AdminLoginDTO;
import com.cdwater.toolshare.entity.Admin;
import com.cdwater.toolshare.mapper.AdminMapper;
import com.cdwater.toolshare.vo.AdminVO;
import com.cdwater.toolshare.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AdminMapper adminMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 管理端登录：账号不存在 / 密码错误统一提示，不泄露账号信息
     */
    public LoginVO login(AdminLoginDTO dto) {
        Admin admin = adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, dto.getUsername()));
        if (admin == null || !passwordEncoder.matches(dto.getPassword(), admin.getPassword())) {
            throw new BizException(ResultCode.BAD_REQUEST, "账号或密码错误");
        }
        if (admin.getStatus() == null || admin.getStatus() == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已禁用");
        }
        StpKit.ADMIN.login(admin.getId());
        SaTokenInfo info = StpKit.ADMIN.getTokenInfo();
        return new LoginVO(info.tokenName, info.tokenValue);
    }

    /** 当前登录管理员信息 */
    public AdminVO info() {
        Admin admin = adminMapper.selectById(StpKit.ADMIN.getLoginIdAsLong());
        if (admin == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "管理员不存在");
        }
        if (admin.getStatus() == null || admin.getStatus() == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已禁用");
        }
        AdminVO vo = new AdminVO();
        vo.setId(admin.getId());
        vo.setUsername(admin.getUsername());
        return vo;
    }

    /** 当前会话注销 */
    public void logout() {
        StpKit.ADMIN.logout();
    }
}
