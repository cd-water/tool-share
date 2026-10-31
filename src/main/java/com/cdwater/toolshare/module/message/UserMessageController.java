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

@RestController
@RequestMapping("/api/user/messages")
@RequiredArgsConstructor
public class UserMessageController {

    private final MessageService messageService;

    @GetMapping
    public Result<PageVO<MessageVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(messageService.page("USER", StpKit.USER.getLoginIdAsLong(), page, size));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(messageService.unreadCount("USER", StpKit.USER.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        messageService.markRead("USER", StpKit.USER.getLoginIdAsLong(), id);
        return Result.success();
    }

    @PostMapping("/read-all")
    public Result<Void> readAll() {
        messageService.markAllRead("USER", StpKit.USER.getLoginIdAsLong());
        return Result.success();
    }
}
