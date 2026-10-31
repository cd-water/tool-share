package com.cdwater.toolshare.shared.config;

import cn.dev33.satoken.config.SaTokenConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SaTokenConfigTest {

    @Test
    void user_config_matches_spec() {
        SaTokenConfig c = StpKit.USER.getConfig();
        assertEquals("user", StpKit.USER.getLoginType());
        assertEquals("Authorization", c.getTokenName());
        assertEquals("Bearer", c.getTokenPrefix());
        assertEquals(5184000L, c.getTimeout());
        assertEquals(2592000L, c.getActiveTimeout());
        assertEquals("uuid", c.getTokenStyle());
        assertTrue(c.getIsReadHeader());
        assertFalse(c.getIsReadCookie());
        assertFalse(c.getIsReadBody());
        assertTrue(c.getIsConcurrent());
        assertFalse(c.getIsShare());
        assertTrue(c.getAutoRenew());
        assertTrue(c.getIsLog());
    }

    @Test
    void admin_config_matches_spec() {
        SaTokenConfig c = StpKit.ADMIN.getConfig();
        assertEquals("admin", StpKit.ADMIN.getLoginType());
        assertEquals("Authorization", c.getTokenName());
        assertEquals("Bearer", c.getTokenPrefix());
        assertEquals(1296000L, c.getTimeout());
        assertEquals(604800L, c.getActiveTimeout());
        assertEquals("random-64", c.getTokenStyle());
        assertTrue(c.getIsReadHeader());
        assertFalse(c.getIsReadCookie());
        assertFalse(c.getIsReadBody());
        assertTrue(c.getIsConcurrent());
        assertFalse(c.getIsShare());
        assertTrue(c.getAutoRenew());
        assertTrue(c.getIsLog());
    }

    @Test
    void two_logics_use_independent_config_instances() {
        assertNotSame(StpKit.USER.getConfig(), StpKit.ADMIN.getConfig());
    }
}
