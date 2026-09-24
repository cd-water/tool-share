package com.cdwater.toolshare.common.exception;

import com.cdwater.toolshare.common.enums.ResultCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }
}
