package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.config.StpKit;
import com.cdwater.toolshare.service.MessageService;
import com.cdwater.toolshare.vo.MessageVO;
import com.cdwater.toolshare.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/messages")
@RequiredArgsConstructor
public class AdminMessageController {

    private final MessageService messageService;

    @GetMapping
    public Result<PageVO<MessageVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(messageService.page("ADMIN", StpKit.ADMIN.getLoginIdAsLong(), page, size));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(messageService.unreadCount("ADMIN", StpKit.ADMIN.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        messageService.markRead("ADMIN", StpKit.ADMIN.getLoginIdAsLong(), id);
        return Result.success();
    }

    @PostMapping("/read-all")
    public Result<Void> readAll() {
        messageService.markAllRead("ADMIN", StpKit.ADMIN.getLoginIdAsLong());
        return Result.success();
    }
}
