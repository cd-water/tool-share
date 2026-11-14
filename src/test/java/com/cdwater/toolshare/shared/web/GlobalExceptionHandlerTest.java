package com.cdwater.toolshare.shared.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void known_business_codes_map_to_semantic_status() {
        assertEquals(400, statusOf("C400"));
        assertEquals(401, statusOf("C401"));
        assertEquals(403, statusOf("C403"));
        assertEquals(404, statusOf("C404"));
        assertEquals(409, statusOf("C409"));
        assertEquals(429, statusOf("C429"));
        assertEquals(500, statusOf("S500"));
    }

    @Test
    void unmapped_code_falls_back_by_prefix() {
        assertEquals(400, statusOf("X123"));
        assertEquals(500, statusOf("S999"));
        assertEquals(400, GlobalExceptionHandler.statusFor(null).value());
    }

    private int statusOf(String code) {
        return handler.handleBiz(new BizException(code, "test")).getStatusCode().value();
    }
}
