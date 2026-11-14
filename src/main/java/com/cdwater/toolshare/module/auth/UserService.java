/**
 * 用户认证与资料文件。
 *
 * <h2>登录方式</h2>
 * <p>手机号验证码登录（不存在则静默注册）与手机号密码登录。
 * 两种方式的失败文案都统一，不泄露手机号是否已注册。
 *
 * <h2>验证码</h2>
 * <p>6 位数字，Redis 保存 5 分钟；发送时覆盖旧码并清零错误计数。
 * 校验与消费由 Lua 脚本原子完成：正确即删除，错误累加次数，达到上限删除验证码。
 * 发送频率按手机号分钟/小时与来源 IP 小时三个维度限制。
 *
 * <h2>密码与证件</h2>
 * <p>密码只保存 BCrypt 密文；静默注册用户没有密码。
 * 身份证号以 enc: 前缀 AES 密文保存，读取时统一脱敏，
 * 历史明文数据也能通过 IdCardCipher 兼容处理。
 *
 * <h2>登录态</h2>
 * <p>使用 Sa-Token 用户端体系，与管理端完全隔离；
 * 每次读取用户信息都重新检查账号状态，禁用后立即生效。
 *
 * <h2>并发</h2>
 * <p>验证码的一次性语义由 Redis Lua 保证，并发登录最多一个成功；
 * 限流计数使用 INCR + 首次设置 TTL，允许极端情况下计数键无过期时间。
 */
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

/**
 * 用户认证与个人资料服务。
 * <p>职责：验证码生成/校验、手机号验证码登录、密码登录、静默注册、
 * 当前用户信息与资料更新。
 * <p>关键约束：
 * <ul>
 *   <li>验证码一次性消费，校验与删除在 Lua 脚本中原子完成；</li>
 *   <li>登录失败文案统一，不泄露手机号是否已注册；</li>
 *   <li>身份证号只以密文落库，对外统一脱敏；</li>
 *   <li>验证码发送按手机号分钟/小时与 IP 小时三个维度限流。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    // 验证码键：captcha:user:{phone}，值为 6 位数字，TTL 5 分钟
    private static final String CAPTCHA_KEY = "captcha:user:";
    // 错误尝试计数键：captcha:attempt:user:{phone}
    private static final String CAPTCHA_ATTEMPT_KEY = "captcha:attempt:user:";
    // 手机号分钟级限流键
    private static final String RATE_PHONE_MINUTE = "captcha:rl:phone:min:";
    // 手机号小时级限流键
    private static final String RATE_PHONE_HOUR = "captcha:rl:phone:hour:";
    // 来源 IP 小时级限流键
    private static final String RATE_IP_HOUR = "captcha:rl:ip:hour:";
    // 验证码有效期；错误计数键使用相同 TTL，避免计数长期滞留
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    // 静默注册昵称的字符表，仅小写字母与数字
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

    // 用户表访问
    private final UserMapper userMapper;
    // 验证码、错误计数与限流计数都放在 Redis
    private final StringRedisTemplate stringRedisTemplate;
    // 身份证加解密与脱敏；对历史明文数据也能兼容
    private final IdCardCipher idCardCipher;
    // 视图映射，统一在映射阶段完成手机号与身份证脱敏
    private final UserVoMapper userVoMapper;
    // 验证码暴露开关、最大尝试次数与频控阈值
    private final AuthProperties authProperties;
    // 验证码与随机昵称使用密码学安全随机源
    private final SecureRandom random = new SecureRandom();
    // 密码只保存 BCrypt 密文；静默注册用户没有密码
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
      * <p>每次发送都会覆盖旧验证码并清空错误计数，
      * 因此重复发送会让旧验证码立即失效。
      *
      * @param phone 接收手机号
      * @param ip    来源 IP，用于小时级限流
      * @return 暴露开关打开时返回验证码，否则返回 null
      * @throws BizException 触发任一频控阈值时返回 429
     * 发送验证码（MOCK 短信：无真实通道，验证码写入日志）。
     * 开关打开时直接返回 6 位验证码；关闭时返回 null，验证码只存在于服务端与日志。
     */
    public String sendCode(String phone, String ip) {
        // 发送前先做三维度频控，任何一维超限直接 429
        checkRateLimit(phone, ip);
        // 使用 SecureRandom 生成 6 位数字，保留前导零
        String code = String.format("%06d", random.nextInt(1_000_000));
        // 覆盖旧验证码，使之前发送的验证码立即失效
        stringRedisTemplate.opsForValue().set(CAPTCHA_KEY + phone, code, CAPTCHA_TTL);
        // 新验证码从 0 开始计算错误次数
        stringRedisTemplate.delete(CAPTCHA_ATTEMPT_KEY + phone);
        // 当前无真实短信通道，验证码只写日志；生产接入短信服务后应移除
        log.info("[MOCK SMS] phone={}, code={}", phone, code);
        return authProperties.exposeCaptcha() ? code : null;
    }

    /**
     * 三维度发送频控：手机号每分钟、手机号每小时、IP 每小时。
     * <p>任一维度超限即拒绝，且已经自增的计数不回收，
     * 这是有意的：防止攻击者在边界反复试探。
     *
     * @param phone 手机号
     * @param ip    来源 IP
     */
    private void checkRateLimit(String phone, String ip) {
        // 阈值全部来自配置，便于按环境调整
        AuthProperties.RateLimit limit = authProperties.rateLimit();
        if (increment(RATE_PHONE_MINUTE + phone, 60) > limit.phonePerMinute()
                || increment(RATE_PHONE_HOUR + phone, 3600) > limit.phonePerHour()
                || increment(RATE_IP_HOUR + ip, 3600) > limit.ipPerHour()) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, "发送太频繁，请稍后再试");
        }
    }

    /**
     * 原子自增计数器，并在首次创建时设置 TTL。
     * <p>INCR 与 EXPIRE 分两步执行：极端情况下若首次 EXPIRE 失败，
     * 计数键可能无过期时间，但限流本身仍生效，最坏结果是该维度长期限流。
     *
     * @param key        Redis 键
     * @param ttlSeconds 首次创建时设置的过期秒数
     * @return 自增后的值；Redis 异常返回 0
     */
    private long increment(String key, long ttlSeconds) {
        // 自增是原子操作，并发下不会丢失计数
        Long value = stringRedisTemplate.opsForValue().increment(key);
        // 仅首次创建时设置 TTL，避免每次自增都 prolong 窗口
        if (value != null && value == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        }
        // Redis 异常时按 0 处理，实际会由调用方比较阈值（可能放行，见类注释的权衡）
        return value == null ? 0 : value;
    }

    /**
      * <p>验证码由 Lua 脚本原子校验并消费：并发请求中最多只有一个能成功。
      * 手机号首次出现时自动创建用户，不要求用户感知注册流程。
      *
      * @param dto 手机号与验证码
      * @return 登录成功后的 token 信息
      * @throws BizException 验证码错误/过期（400）或账号被禁用（403）
     * 手机号 + 验证码登录，用户不存在则静默注册（密码为空、昵称自动生成、状态启用）
     */
    public LoginVO loginByCode(CodeLoginDTO dto) {
        // Lua 脚本原子完成“比对 + 删除/计数”，并发下最多一个请求成功
        Long verified = stringRedisTemplate.execute(VERIFY_CAPTCHA_SCRIPT,
                List.of(CAPTCHA_KEY + dto.getPhone(), CAPTCHA_ATTEMPT_KEY + dto.getPhone()),
                dto.getCode(),
                String.valueOf(CAPTCHA_TTL.toSeconds()),
                String.valueOf(authProperties.maxAttempts()));
        // 0 表示验证码错误、已过期或尝试次数已达上限
        if (verified == null || verified != 1L) {
            throw new BizException(ResultCode.BAD_REQUEST, "验证码错误或已过期");
        }

        // 手机号是用户唯一标识；不存在时立即静默注册
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        if (user == null) {
            user = new User();
            user.setPhone(dto.getPhone());
            // 随机昵称避免默认昵称撞车，用户可在个人中心修改
            user.setNickname("user_" + randomNickname());
            // 头像统一用空串而不是 null，简化前端判断
            user.setAvatar("");
            // 新用户默认启用
            user.setStatus(1);
            // 插入后回填主键，随即用于登录
            userMapper.insert(user);
        }
        checkStatus(user.getStatus());
        return doLogin(user.getId());
    }

    /**
     * 建立用户端登录会话并返回 token。
     * <p>包级可见，供本包内其它认证入口复用。
     *
     * @param userId 用户主键
     * @return token 名称与值
     */
    LoginVO doLogin(Long userId) {
        // loginId 使用用户主键，登录类型与管理端隔离
        StpKit.USER.login(userId);
        SaTokenInfo info = StpKit.USER.getTokenInfo();
        return new LoginVO(info.tokenName, info.tokenValue);
    }

    /**
      * <p>“用户不存在”“未设置密码”“密码错误”三种情况共用同一文案与状态码，
      * 避免攻击者通过响应差异枚举已注册手机号。
      *
      * @param dto 手机号与密码
      * @return 登录成功后的 token 信息
      * @throws BizException 凭据错误（400）或账号被禁用（403）
     * 手机号 + 密码登录：账号不存在 / 未设置密码 / 密码错误，统一返回同一提示，不泄露注册信息
     */
    public LoginVO loginByPwd(PwdLoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        // 用户不存在、未设置密码、密码错误共用同一分支与文案
        if (user == null
                || !StringUtils.hasText(user.getPassword())
                || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException(ResultCode.BAD_REQUEST, "手机号或密码错误");
        }
        checkStatus(user.getStatus());
        return doLogin(user.getId());
    }

    /**
     * 查询当前登录用户信息。
     * <p>每次读取都重新检查账号状态，保证禁用后立即生效。
     *
     * @return 脱敏后的用户视图
     * @throws BizException 用户不存在（400）或已禁用（403）
     */
    /** 当前登录用户信息 */
    public UserVO info() {
        User user = userMapper.selectById(StpKit.USER.getLoginIdAsLong());
        if (user == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        checkStatus(user.getStatus());
        return toVO(user);
    }

    /**
     * 个人资料与 {@link #info()} 共用同一口径，均返回脱敏数据。
     *
     * @return 脱敏后的用户资料
     */
    /** 个人资料（身份证脱敏） */
    public UserVO profile() {
        return info();
    }

    /**
     * 更新当前用户个人资料。
     * <p>昵称与真实姓名会 trim；头像允许设为空串；
     * 身份证号仅在非空时加密写入，留空表示保留原值。
     *
     * @param dto 可选的资料字段
     * @return 更新后的脱敏资料
     * @throws BizException 用户不存在时返回 400
     */
    /** 更新个人资料；身份证留空表示不修改 */
    public UserVO updateProfile(ProfileUpdateDTO dto) {
        User user = userMapper.selectById(StpKit.USER.getLoginIdAsLong());
        if (user == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        // 空白昵称视为未修改，避免把昵称清空
        if (StringUtils.hasText(dto.getNickname())) {
            user.setNickname(dto.getNickname().trim());
        }
        // 头像允许显式空串，表示清空头像
        if (dto.getAvatar() != null) {
            user.setAvatar(dto.getAvatar());
        }
        // 真实姓名允许修改，保存前 trim
        if (dto.getRealName() != null) {
            user.setRealName(dto.getRealName().trim());
        }
        if (StringUtils.hasText(dto.getIdCard())) {
            // 身份证只在非空时更新；加密后落库，绝不保存明文
            user.setIdCard(idCardCipher.encrypt(dto.getIdCard().trim()));
        }
        // 资料更新失败会抛异常并回滚
        userMapper.updateById(user);
        return toVO(user);
    }

    /**
     * 实体转视图，统一走 MapStruct 的脱敏后置处理。
     *
     * @param user 用户实体
     * @return 脱敏后的视图
     */
    private UserVO toVO(User user) {
        // 手机号与身份证在映射器的后置处理中脱敏
        return userVoMapper.toVO(user, idCardCipher);
    }

    /**
     * 注销当前用户会话。
     */
    /** 当前会话注销 */
    public void logout() {
        // 立即注销当前 token，不等待过期
        StpKit.USER.logout();
    }

    /**
     * 账号启用校验：null 或 0 均视为禁用。
     * <p>包级可见，供同包登录入口复用。
     *
     * @param status 用户状态
     * @throws BizException 账号被禁用时返回 403
     */
    void checkStatus(Integer status) {
        // null 按禁用处理，防止历史脏数据绕过检查
        if (status == null || status == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已禁用");
        }
    }

    /**
     * 生成 6 位随机昵称后缀，前缀由调用方拼接为 {@code user_}。
     * <p>仅用于避免昵称重复，不承担身份识别作用。
     *
     * @return 6 位小写字母/数字串
     */
    private String randomNickname() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            // 从固定字符表取样，保证昵称只含字母数字
            sb.append(NICKNAME_CHARS.charAt(random.nextInt(NICKNAME_CHARS.length())));
        }
        return sb.toString();
    }
}
