package com.cdwater.toolshare.user.service;

import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.user.dto.LoginDTO;
import com.cdwater.toolshare.user.dto.RegisterDTO;
import com.cdwater.toolshare.user.dto.UserQueryDTO;
import com.cdwater.toolshare.user.dto.UserRoleDTO;
import com.cdwater.toolshare.user.dto.UserStatusDTO;
import com.cdwater.toolshare.user.vo.LoginVO;
import com.cdwater.toolshare.user.vo.UserVO;

/**
 * 用户业务接口
 */
public interface UserService {

    LoginVO login(LoginDTO dto);

    void register(RegisterDTO dto);

    UserVO me();

    PageResult<UserVO> page(UserQueryDTO query);

    void updateRole(Long id, UserRoleDTO dto);

    void updateStatus(Long id, UserStatusDTO dto);
}
