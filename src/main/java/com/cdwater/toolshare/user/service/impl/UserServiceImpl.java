package com.cdwater.toolshare.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.enums.ResultCode;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.user.dto.LoginDTO;
import com.cdwater.toolshare.user.dto.RegisterDTO;
import com.cdwater.toolshare.user.dto.UserQueryDTO;
import com.cdwater.toolshare.user.dto.UserRoleDTO;
import com.cdwater.toolshare.user.dto.UserStatusDTO;
import com.cdwater.toolshare.user.entity.User;
import com.cdwater.toolshare.user.mapper.UserMapper;
import com.cdwater.toolshare.user.service.UserService;
import com.cdwater.toolshare.common.util.DesensitizeUtil;
import com.cdwater.toolshare.user.vo.LoginVO;
import com.cdwater.toolshare.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 用户业务实现
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public LoginVO login(LoginDTO dto) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (user == null || !BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "用户名或密码错误");
        }
        if (Objects.equals(user.getStatus(), 0)) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "账号已停用");
        }
        StpUtil.login(user.getId());
        LoginVO vo = new LoginVO();
        vo.setToken(StpUtil.getTokenValue());
        vo.setUserId(user.getId());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        return vo;
    }

    @Override
    public void register(RegisterDTO dto) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setPhone(dto.getPhone());
        user.setIdCard(dto.getIdCard());
        user.setRole(0);
        user.setStatus(1);
        userMapper.insert(user);
    }

    @Override
    public UserVO me() {
        User user = userMapper.selectById(StpUtil.getLoginIdAsLong());
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setPhone(DesensitizeUtil.maskPhone(user.getPhone()));
        vo.setRole(user.getRole());
        return vo;
    }

    @Override
    public PageResult<UserVO> page(UserQueryDTO query) {
        Page<User> page = userMapper.selectPage(query.toPage(),
                new LambdaQueryWrapper<User>()
                        .like(StringUtils.hasText(query.getRealName()), User::getRealName, query.getRealName())
                        .eq(query.getRole() != null, User::getRole, query.getRole())
                        .orderByDesc(User::getCreateTime));
        return PageResult.of(page.convert(this::toVO));
    }

    @Override
    public void updateRole(Long id, UserRoleDTO dto) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        if (Objects.equals(id, StpUtil.getLoginIdAsLong()) && dto.getRole() < user.getRole()) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "不能给自己降级");
        }
        user.setRole(dto.getRole());
        userMapper.updateById(user);
    }

    @Override
    public void updateStatus(Long id, UserStatusDTO dto) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        user.setStatus(dto.getStatus());
        userMapper.updateById(user);
    }

    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setPhone(DesensitizeUtil.maskPhone(user.getPhone()));
        vo.setRole(user.getRole());
        return vo;
    }
}
