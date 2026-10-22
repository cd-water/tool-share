package com.cdwater.toolshare.common.result;

import com.cdwater.toolshare.common.enums.ResultCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 统一响应包装测试。
 */
class ResultTest {

    @Test
    void success_hasSuccessCodeAndNullData() {
        Result<Void> result = Result.success();

        assertEquals("A200", result.getCode());
        assertEquals("Success", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void success_carriesData() {
        Result<String> result = Result.success("payload");

        assertEquals("A200", result.getCode());
        assertEquals("payload", result.getData());
    }

    @Test
    void fail_fromResultCode() {
        Result<Void> result = Result.fail(ResultCode.NOT_FOUND);

        assertEquals("C404", result.getCode());
        assertEquals("Not Found", result.getMessage());
    }

    @Test
    void fail_withCustomCodeAndMessage() {
        Result<Void> result = Result.fail("C409", "该时段已被预约");

        assertEquals("C409", result.getCode());
        assertEquals("该时段已被预约", result.getMessage());
    }
}
