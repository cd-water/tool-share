package com.cdwater.toolshare.module.message;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.cdwater.toolshare.shared.web.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 消息渠道配置：仅系统管理员可读写。 */
@RestController
@RequestMapping("/api/admin/message-config")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "message:config")
public class AdminMessageConfigController {

    private final MessageConfigService configService;

    @GetMapping
    public Result<MessageConfigVO> get() {
        return Result.success(configService.get());
    }

    @PutMapping
    public Result<MessageConfigVO> update(@Valid @RequestBody MessageConfigUpdateDTO dto) {
        return Result.success(configService.update(dto));
    }
}
