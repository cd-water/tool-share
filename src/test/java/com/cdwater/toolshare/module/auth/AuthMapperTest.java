package com.cdwater.toolshare.module.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证相关 Mapper 的种子数据读取测试。
 * <p>
 * 测试范围：管理员表与用户表在测试种子数据下的查询行为，
 * 覆盖登录链路实际依赖的“按用户名查管理员”“按手机号查用户”两条检索路径。
 * <p>
 * 依赖环境：完整 Spring 上下文、测试数据库，以及应用启动阶段写入的 test-seed 种子数据；
 * 本类只做只读断言，不插入或修改记录，因此可以安全重复执行。
 * <p>
 * 关键不变量：
 * 1. 种子管理员与密码登录用户必须存在，且查询条件能精确命中唯一记录；
 * 2. 密码以 BCrypt 哈希存储（$2a$ 前缀），任何情况下都不能落明文；
 * 3. 账号状态必须为启用（1），否则会在登录服务层被前置拒绝；
 * 4. 仅验证码登录的种子用户允许 password 为 null，登录流程需兼容该情况；
 * 5. Mapper 查询结果直接决定认证分支，字段缺失或类型漂移都应被本测试发现。
 * <p>
 * 测试边界：只验证“种子数据 + 查询语句 + 实体映射”这一段，
 * 不启动 Web 层，也不校验密码正确性与令牌签发；
 * 那些行为由登录集成测试负责，避免本类职责过宽。
 * 断言失败时优先检查种子初始化脚本与数据库迁移是否同步。
 */
@SpringBootTest
@Import(TestStorageConfig.class)
class AuthMapperTest extends AbstractIntegrationTest {

    /**
     * 用户表 Mapper，用于验证种子用户的查询结果。
     * 由 Spring 注入真实的 MyBatis-Plus 代理，确保查询条件解析与生产一致。
     * 使用真实 Mapper 而非 mock，是为了同时覆盖实体与表结构的映射关系。
     */
    @Autowired
    private UserMapper userMapper;

    /**
     * 管理员表 Mapper，用于验证种子管理员的查询结果。
     * 与管理端登录服务使用同一张表，断言结果即代表真实登录数据形态。
     */
    @Autowired
    private AdminMapper adminMapper;

    /**
     * 场景：使用管理端登录时提交的用户名 “admin” 查询管理员记录。
     * 前置条件：测试种子已写入管理员账号，且该用户名在表中唯一。
     * 断言目标：
     * 1. 查询返回唯一一条管理员记录，证明种子数据与检索路径都可用；
     * 2. 密码字段以 $2a$ 开头，证明落库的是 BCrypt 哈希而非明文；
     * 3. status=1，表示账号启用，可以通过后续的登录状态校验。
     * 本用例同时充当管理端登录链路的“数据前置检查”。
     */
    @Test
    void select_seed_admin_by_username() {
        // 按用户名精确匹配，对应登录服务定位管理员的第一个步骤。
        Admin admin = adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, "admin"));
        // 记录必须存在；为空说明种子数据缺失，后续密码校验无从谈起。
        // selectOne 在命中多条时也会抛异常，因此这里同时隐含“用户名唯一”的约束。
        assertNotNull(admin);
        // BCrypt 哈希固定以 $2a$ 标识算法版本与成本参数，避免明文密码泄露。
        assertTrue(admin.getPassword().startsWith("$2a$"));
        // 只有状态 1（启用）的账号才允许登录，禁用账号应被服务层拒绝。
        assertEquals(1, admin.getStatus());
    }

    /**
     * 场景：分别按手机号查询两个种子用户，覆盖密码登录与验证码登录两种账号形态。
     * 前置条件：13800000001 走密码登录，13800000002 走验证码登录，手机号均唯一。
     * 断言目标：
     * 1. 两个手机号都能查到记录，说明用户端登录的检索路径可用；
     * 2. 密码登录用户拥有 BCrypt 哈希，可参与密码比对；
     * 3. 验证码登录用户密码为 null，但记录本身完整可查，
     * 登录服务必须允许“无密码但有手机号”的账号通过验证码通道进入。
     */
    @Test
    void select_seed_users() {
        // 密码登录用户：手机号唯一，查询应精确命中一条记录。
        User u1 = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, "13800000001"));
        // 基础资料存在且昵称与种子一致，说明映射字段没有错位。
        assertNotNull(u1);
        assertEquals("user_mjafo8", u1.getNickname());
        // 密码同样以 BCrypt 哈希保存，供 PasswordEncoder 校验。
        assertTrue(u1.getPassword().startsWith("$2a$"));

        // 验证码登录用户：以手机号作为唯一身份，不依赖密码字段。
        User u2 = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, "13800000002"));
        // 记录必须存在，否则验证码登录会因找不到用户而失败。
        // 与管理员相同，手机号在业务上也应唯一，selectOne 会暴露重复数据。
        assertNotNull(u2);
        // 密码为空是合法状态，不应被当成脏数据或导致空指针。
        // 若未来改为强制密码，这条断言会提醒同步调整验证码登录的兼容逻辑。
        assertNull(u2.getPassword());
    }
}
