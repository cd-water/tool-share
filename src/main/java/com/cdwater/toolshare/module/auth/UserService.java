package com.cdwater.toolshare.module.auth;

import cn.dev33.satoken.stp.SaTokenInfo;
import com.cdwater.toolshare.shared.security.IdCardCipher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.module.auth.CodeLoginDTO;
import com.cdwater.toolshare.module.auth.ProfileUpdateDTO;
import com.cdwater.toolshare.module.auth.PwdLoginDTO;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.auth.LoginVO;
import com.cdwater.toolshare.module.auth.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String CAPTCHA_KEY = "captcha:user:";
    private static final String CAPTCHA_ATTEMPT_KEY = "captcha:attempt:user:";
    private static final String RATE_PHONE_MINUTE = "captcha:rl:phone:min:";
    private static final String RATE_PHONE_HOUR = "captcha:rl:phone:hour:";
    private static final String RATE_IP_HOUR = "captcha:rl:ip:hour:";
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final String NICKNAME_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";

    /**
     * 原子校验并消费验证码：正确即删除（等价 GETDEL 的一次性语义，并发下仅一个请求成功）；
     * 错误则累加尝试次数，达到上限时删除验证码。
     */
    private static final DefaultRedisScript<Long> VERIFY_CAPTCHA_SCRIPT = new DefaultRedisScript<>("""
            local saved = redis.call('GET', KEYS[1])
            if not saved then return 0 end
            if saved == ARGV[1] then
              redis.call('DEL', KEYS[1])
              redis.call('DEL', KEYS[2])
              return 1
            end
            local attempts = redis.call('INCR', KEYS[2])
            if attempts == 1 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end
            if attempts >= tonumber(ARGV[3]) then redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final IdCardCipher idCardCipher;
    private final UserVoMapper userVoMapper;
    private final AuthProperties authProperties;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 发送验证码（MOCK 短信：无真实通道，验证码仅写日志）。
     * 生产模式只返回「已发送」；开发/测试可通过 toolshare.auth.expose-captcha 打开返回。
     */
    public String sendCode(String phone, String ip) {
        checkRateLimit(phone, ip);
        String code = String.format("%06d", random.nextInt(1_000_000));
        stringRedisTemplate.opsForValue().set(CAPTCHA_KEY + phone, code, CAPTCHA_TTL);
        stringRedisTemplate.delete(CAPTCHA_ATTEMPT_KEY + phone);
        log.info("[MOCK SMS] phone={}, code={}", phone, code);
        return authProperties.exposeCaptcha() ? code : "已发送";
    }

    private void checkRateLimit(String phone, String ip) {
        AuthProperties.RateLimit limit = authProperties.rateLimit();
        if (increment(RATE_PHONE_MINUTE + phone, 60) > limit.phonePerMinute()
                || increment(RATE_PHONE_HOUR + phone, 3600) > limit.phonePerHour()
                || increment(RATE_IP_HOUR + ip, 3600) > limit.ipPerHour()) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, "发送太频繁，请稍后再试");
        }
    }

    private long increment(String key, long ttlSeconds) {
        Long value = stringRedisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        }
        return value == null ? 0 : value;
    }

    /**
     * 手机号 + 验证码登录，用户不存在则静默注册（密码为空、昵称自动生成、状态启用）
     */
    public LoginVO loginByCode(CodeLoginDTO dto) {
        Long verified = stringRedisTemplate.execute(VERIFY_CAPTCHA_SCRIPT,
                List.of(CAPTCHA_KEY + dto.getPhone(), CAPTCHA_ATTEMPT_KEY + dto.getPhone()),
                dto.getCode(),
                String.valueOf(CAPTCHA_TTL.toSeconds()),
                String.valueOf(authProperties.maxAttempts()));
        if (verified == null || verified != 1L) {
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
            user.setIdCard(idCardCipher.encrypt(dto.getIdCard().trim()));
        }
        userMapper.updateById(user);
        return toVO(user);
    }

    private UserVO toVO(User user) {
        return userVoMapper.toVO(user, idCardCipher);
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
