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

/**
 * 管理端消息渠道配置控制器。
 * <p>只有具备 {@code message:config} 权限的系统管理员可以读写；配置项
 * 包括默认渠道与短信总开关。读取时若数据库尚未初始化配置，会回退到
 * application.yml 中的默认值。
 * <p>更新接口使用 PUT 语义整体覆盖两项配置；保存后立即影响后续消息的
 * <p>配置为全局单行数据，所有居民与管理员共享。
 * 渠道选择，不需要重启应用。
 */
@RestController
@RequestMapping("/api/admin/message-config")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "message:config")
public class AdminMessageConfigController {
    // 读取与更新都返回同一视图结构。
    // 配置为全局单行数据。
    // 更新后立即影响后续消息的渠道选择。
    // 配置缺失时读取会回退到 yml 默认值。

    /** 消息渠道配置服务。 */
    private final MessageConfigService configService;

    /**
     * 读取当前消息渠道配置。
     *
     * @return 默认渠道与短信开关
     * <p>返回值经过枚举规范化，前端可直接回显。
     */
    @GetMapping
    public Result<MessageConfigVO> get() {
        return Result.success(configService.get());
    }

    /**
     * 更新消息渠道配置。
     *
     * @param dto 新的渠道与短信开关
     * @return 更新后的配置
     * <p>渠道非法时返回 400，不会写入数据库。
     */
    @PutMapping
    public Result<MessageConfigVO> update(@Valid @RequestBody MessageConfigUpdateDTO dto) {
        return Result.success(configService.update(dto));
    }
}
