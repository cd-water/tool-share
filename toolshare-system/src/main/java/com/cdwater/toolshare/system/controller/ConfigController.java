package com.cdwater.toolshare.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.system.dto.ConfigItem;
import com.cdwater.toolshare.system.dto.ConfigUpdateRequest;
import com.cdwater.toolshare.system.service.ConfigService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/system/configs")
public class ConfigController {

    private final ConfigService configService;

    @SaCheckRole("ADMIN")
    @GetMapping
    public Result<List<ConfigItem>> list() {
        return Result.success(configService.list());
    }

    @SaCheckRole("ADMIN")
    @PutMapping("/{key}")
    public Result<Void> update(@PathVariable String key, @Valid @RequestBody ConfigUpdateRequest request) {
        configService.update(key, request.getConfigValue());
        return Result.success();
    }
}
