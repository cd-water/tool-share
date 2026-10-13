package com.cdwater.toolshare.notify.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.notify.dto.NotifyConfigDTO;
import com.cdwater.toolshare.notify.dto.NotifyQueryDTO;
import com.cdwater.toolshare.notify.entity.Message;
import com.cdwater.toolshare.notify.service.NotifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息提醒接口
 */
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final NotifyService notifyService;

    /** 我的消息分页 */
    @GetMapping("/page")
    public Result<PageResult<Message>> page(@Valid NotifyQueryDTO query) {
        return Result.success(notifyService.page(query));
    }

    /** 标记已读 */
    @PutMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notifyService.read(id);
        return Result.success();
    }

    /** 全部标记已读 */
    @PutMapping("/read-all")
    public Result<Void> readAll() {
        notifyService.readAll();
        return Result.success();
    }

    /** 查询默认提醒方式配置 */
    @SaCheckRole(value = {RoleConstant.STAFF, RoleConstant.ADMIN}, mode = SaMode.OR)
    @GetMapping("/config")
    public Result<NotifyConfigDTO> getConfig() {
        return Result.success(notifyService.getConfig());
    }

    /** 修改默认提醒方式 */
    @SaCheckRole(value = {RoleConstant.STAFF, RoleConstant.ADMIN}, mode = SaMode.OR)
    @PutMapping("/config")
    public Result<Void> updateConfig(@Valid @RequestBody NotifyConfigDTO dto) {
        notifyService.updateConfig(dto);
        return Result.success();
    }
}
