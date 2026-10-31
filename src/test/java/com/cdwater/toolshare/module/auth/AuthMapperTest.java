package com.cdwater.toolshare.module.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AuthMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AdminMapper adminMapper;

    @Test
    void select_seed_admin_by_username() {
        Admin admin = adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, "admin"));
        assertNotNull(admin);
        assertTrue(admin.getPassword().startsWith("$2a$"));
        assertEquals(1, admin.getStatus());
    }

    @Test
    void select_seed_users() {
        User u1 = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, "13800000001"));
        assertNotNull(u1);
        assertEquals("user_mjafo8", u1.getNickname());
        assertTrue(u1.getPassword().startsWith("$2a$"));

        User u2 = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, "13800000002"));
        assertNotNull(u2);
        assertNull(u2.getPassword());
    }
}
