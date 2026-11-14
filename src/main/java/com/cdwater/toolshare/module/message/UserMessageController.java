package com.cdwater.toolshare.module.message;

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
 * 居民端消息中心控制器。
 * <p>提供消息分页、未读数、单条已读与全部已读接口，挂载在
 * {@code /api/user/messages} 下。接收人身份完全取自会话，居民之间
 * <p>接口不接收 receiverId 参数，所有查询与更新都使用会话用户 ID；
 * <p>已读操作是幂等的，客户端可以安全重试；全部已读只影响未读记录。
 * <p>消息列表与未读数使用相同的接收人条件，角标与列表始终一致。
 * 即使前端传入他人 ID 也不会被使用。消息归属校验在服务层完成。
 * 的消息天然隔离。
 */
@RestController
@RequestMapping("/api/user/messages")
@RequiredArgsConstructor
public class UserMessageController {

    /** 消息中心服务。 */
    private final MessageService messageService;

    /**
     * 分页查询当前居民的消息，按创建时间倒序。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 消息分页结果
     * <p>列表按创建时间倒序，前端可直接用于消息中心时间线。
     */
    @GetMapping
    public Result<PageVO<MessageVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(messageService.page("USER", StpKit.USER.getLoginIdAsLong(), page, size));
    }

    /**
     * 查询当前居民的未读消息数，用于导航角标。
     *
     * @return 未读数量
     * <p>与列表使用相同的接收人条件，角标与列表保持一致。
     */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(messageService.unreadCount("USER", StpKit.USER.getLoginIdAsLong()));
    }

    /**
     * 将当前居民的一条消息标记为已读。
     * <p>服务层会校验消息归属，非本人消息返回 404。
     * <p>重复标记已读是幂等的，不会报错。
     *
     * @param id 消息主键
     * @return 空结果
     */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        messageService.markRead("USER", StpKit.USER.getLoginIdAsLong(), id);
        return Result.success();
    }

    /**
     * 将当前居民的全部未读消息标记为已读。
     * <p>只影响当前接收人的未读消息。
     *
     * @return 空结果
     */
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        messageService.markAllRead("USER", StpKit.USER.getLoginIdAsLong());
        return Result.success();
    }
}
