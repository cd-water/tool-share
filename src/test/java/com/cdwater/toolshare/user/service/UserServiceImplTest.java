package com.cdwater.toolshare.user.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.user.dto.LoginDTO;
import com.cdwater.toolshare.user.dto.RegisterDTO;
import com.cdwater.toolshare.user.entity.User;
import com.cdwater.toolshare.user.mapper.UserMapper;
import com.cdwater.toolshare.user.service.impl.UserServiceImpl;
import com.cdwater.toolshare.user.vo.LoginVO;
import com.cdwater.toolshare.user.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户业务单元测试（Mock Mapper 与 sa-token 静态上下文）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplTest {

    private static final long USER_ID = 3L;

    @Mock
    private UserMapper userMapper;

    private UserServiceImpl userService;

    private MockedStatic<StpUtil> stp;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper);
        stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
        stp.when(StpUtil::getTokenValue).thenReturn("token-123");
    }

    @AfterEach
    void tearDown() {
        stp.close();
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    @Test
    void login_rejectsUnknownUser() {
        when(userMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> userService.login(loginDto("nobody", "Aa123456")));

        assertEquals("C400", ex.getCode());
        assertEquals("用户名或密码错误", ex.getMessage());
        stp.verify(() -> StpUtil.login(any()), never());
    }

    @Test
    void login_rejectsWrongPassword() {
        User user = user();
        user.setPassword(BCrypt.hashpw("correct-password"));
        when(userMapper.selectOne(any())).thenReturn(user);

        BizException ex = assertThrows(BizException.class, () -> userService.login(loginDto("zhangsan", "wrong")));

        assertEquals("C400", ex.getCode());
        stp.verify(() -> StpUtil.login(any()), never());
    }

    @Test
    void login_returnsTokenAndUserInfo() {
        User user = user();
        user.setPassword(BCrypt.hashpw("Aa123456"));
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginVO vo = userService.login(loginDto("zhangsan", "Aa123456"));

        assertEquals("token-123", vo.getToken());
        assertEquals(USER_ID, vo.getUserId());
        assertEquals("张三", vo.getRealName());
        assertEquals(0, vo.getRole());
        stp.verify(() -> StpUtil.login(USER_ID));
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    @Test
    void register_rejectsDuplicateUsername() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> userService.register(registerDto()));

        assertEquals("C409", ex.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_hashesPasswordAndAssignsResidentRole() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        userService.register(registerDto());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("lisi", saved.getUsername());
        assertTrue(BCrypt.checkpw("Aa123456", saved.getPassword()), "密码应以 BCrypt 存储");
        assertEquals("李四", saved.getRealName());
        assertEquals("13912345678", saved.getPhone());
        assertEquals("310101199001011234", saved.getIdCard());
        assertEquals(0, saved.getRole());       // 居民
        assertEquals(1, saved.getStatus());     // 启用
    }

    // ------------------------------------------------------------------
    // 当前用户
    // ------------------------------------------------------------------

    @Test
    void me_throwsWhenUserMissing() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> userService.me());

        assertEquals("C404", ex.getCode());
    }

    @Test
    void me_masksPhoneAndOmitsPassword() {
        when(userMapper.selectById(USER_ID)).thenReturn(user());

        UserVO vo = userService.me();

        assertEquals(USER_ID, vo.getId());
        assertEquals("zhangsan", vo.getUsername());
        assertEquals("张三", vo.getRealName());
        assertEquals("138****5678", vo.getPhone());
        assertEquals(0, vo.getRole());
    }

    // ------------------------------------------------------------------
    // 测试数据
    // ------------------------------------------------------------------

    private static LoginDTO loginDto(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    private static RegisterDTO registerDto() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("lisi");
        dto.setPassword("Aa123456");
        dto.setRealName("李四");
        dto.setPhone("13912345678");
        dto.setIdCard("310101199001011234");
        return dto;
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("zhangsan");
        user.setRealName("张三");
        user.setPhone("13812345678");
        user.setIdCard("310101199001011234");
        user.setRole(0);
        user.setStatus(1);
        return user;
    }
}
