package com.cdwater.toolshare.module.message;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.message.MessageVO;
import com.cdwater.toolshare.shared.web.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端消息中心控制器。
 * <p>接口形态与居民端一致，但接收人类型固定为 ADMIN，且消息 ID 取自
 * <p>管理端消息主要来自报修提交、维修完成等业务事件；接口形态与居民端
 * <p>管理员消息主要来自报修与维修事件，接口不提供发送能力，避免把
 * <p>已读操作按消息 ID 与当前管理员归属校验。
 * <p>管理端消息只读，发送由业务事件触发，Controller 不暴露写入正文的接口。
 * 消息中心变成任意广播入口。
 * <p>接收人固定为 ADMIN，消息 ID 取自管理员会话，不能查询居民消息。
 * 保持一致，便于前端复用消息列表组件。
 * 管理员会话。挂载在 {@code /api/admin/messages} 下并校验消息管理权限。
 */
@RestController
@RequestMapping("/api/admin/messages")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "message:manage")
public class AdminMessageController {
    // 接收人类型固定为 ADMIN。
    // 消息 ID 取自管理员会话。
    // 列表、未读数与已读操作复用消息服务。
    // 管理端不提供发送正文的接口。

    /** 消息中心服务。 */
    private final MessageService messageService;

    /**
     * 分页查询当前管理员的消息，按创建时间倒序。
     *
     * @param page 页码
     * @param size 每页条数
     * @return 消息分页结果
     * <p>只返回 receiver_type=ADMIN 且 receiver_id 为当前管理员的消息。
     */
    @GetMapping
    public Result<PageVO<MessageVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(messageService.page("ADMIN", StpKit.ADMIN.getLoginIdAsLong(), page, size));
    }

    /**
     * 查询当前管理员的未读消息数。
     *
     * @return 未读数量
     * <p>用于管理端顶部导航角标。
     */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(messageService.unreadCount("ADMIN", StpKit.ADMIN.getLoginIdAsLong()));
    }

    /**
     * 将当前管理员的一条消息标记为已读。
     * <p>消息归属不匹配时返回 404。
     *
     * @param id 消息主键
     * @return 空结果
     */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        messageService.markRead("ADMIN", StpKit.ADMIN.getLoginIdAsLong(), id);
        return Result.success();
    }

    /**
     * 将当前管理员的全部未读消息标记为已读。
     * <p>批量已读只作用于当前管理员。
     *
     * @return 空结果
     */
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        messageService.markAllRead("ADMIN", StpKit.ADMIN.getLoginIdAsLong());
        return Result.success();
    }
}
