package com.cdwater.toolshare.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.common.BizException;
import com.cdwater.toolshare.common.ResultCode;
import com.cdwater.toolshare.config.StpKit;
import com.cdwater.toolshare.dto.CodeLoginDTO;
import com.cdwater.toolshare.dto.ProfileUpdateDTO;
import com.cdwater.toolshare.dto.PwdLoginDTO;
import com.cdwater.toolshare.entity.User;
import com.cdwater.toolshare.mapper.UserMapper;
import com.cdwater.toolshare.vo.LoginVO;
import com.cdwater.toolshare.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String CAPTCHA_KEY = "captcha:user:";
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final String NICKNAME_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final IdCardCipher idCardCipher;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 发送验证码（MOCK 短信：无真实通道，验证码放日志并直接返回给前端便于联调）
     */
    public String sendCode(String phone) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        stringRedisTemplate.opsForValue().set(CAPTCHA_KEY + phone, code, CAPTCHA_TTL);
        log.info("[MOCK SMS] phone={}, code={}", phone, code);
        return code;
    }

    /**
     * 手机号 + 验证码登录，用户不存在则静默注册（密码为空、昵称自动生成、状态启用）
     */
    public LoginVO loginByCode(CodeLoginDTO dto) {
        String key = CAPTCHA_KEY + dto.getPhone();
        // 一次性：原子消费（GETDEL），并发下同一验证码只能成功一次，防重放
        String saved = stringRedisTemplate.opsForValue().getAndDelete(key);
        if (saved == null || !saved.equals(dto.getCode())) {
            throw new BizException(ResultCode.BAD_REQUEST, "验证码错误或已过期");
        }

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        if (user == null) {
            user = new User();
            user.setPhone(dto.getPhone());
            user.setNickname("user_" + randomNickname());
            user.setAvatar("");
            user.setStatus(1);
            userMapper.insert(user);
        }
        checkStatus(user.getStatus());
        return doLogin(user.getId());
    }

    LoginVO doLogin(Long userId) {
        StpKit.USER.login(userId);
        SaTokenInfo info = StpKit.USER.getTokenInfo();
        return new LoginVO(info.tokenName, info.tokenValue);
    }

    /**
     * 手机号 + 密码登录：账号不存在 / 未设置密码 / 密码错误，统一返回同一提示，不泄露注册信息
     */
    public LoginVO loginByPwd(PwdLoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        if (user == null
                || !StringUtils.hasText(user.getPassword())
                || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException(ResultCode.BAD_REQUEST, "手机号或密码错误");
        }
        checkStatus(user.getStatus());
        return doLogin(user.getId());
    }

    /** 当前登录用户信息 */
    public UserVO info() {
        User user = userMapper.selectById(StpKit.USER.getLoginIdAsLong());
        if (user == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        checkStatus(user.getStatus());
        return toVO(user);
    }

    /** 个人资料（身份证脱敏） */
    public UserVO profile() {
        return info();
    }

    /** 更新个人资料；身份证留空表示不修改 */
    public UserVO updateProfile(ProfileUpdateDTO dto) {
        User user = userMapper.selectById(StpKit.USER.getLoginIdAsLong());
        if (user == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        if (StringUtils.hasText(dto.getNickname())) {
            user.setNickname(dto.getNickname().trim());
        }
        if (dto.getAvatar() != null) {
            user.setAvatar(dto.getAvatar());
        }
        if (dto.getRealName() != null) {
            user.setRealName(dto.getRealName().trim());
        }
        if (StringUtils.hasText(dto.getIdCard())) {
            String idCard = dto.getIdCard().trim();
            if (!idCard.matches("^\\d{17}[\\dXx]$")) {
                throw new BizException(ResultCode.BAD_REQUEST, "请输入正确的 18 位身份证号");
            }
            user.setIdCard(idCardCipher.encrypt(idCard));
        }
        userMapper.updateById(user);
        return toVO(user);
    }

    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setPhone(IdCardCipher.maskPhone(user.getPhone()));
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRealName(user.getRealName());
        vo.setIdCard(idCardCipher.mask(user.getIdCard()));
        return vo;
    }

    /** 当前会话注销 */
    public void logout() {
        StpKit.USER.logout();
    }

    void checkStatus(Integer status) {
        if (status == null || status == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已禁用");
        }
    }

    private String randomNickname() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(NICKNAME_CHARS.charAt(random.nextInt(NICKNAME_CHARS.length())));
        }
        return sb.toString();
    }
}
