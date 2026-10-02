package com.cdwater.toolshare.message.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.message.dto.MsgItem;
import com.cdwater.toolshare.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/message/records")
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/my")
    public Result<PageResult<MsgItem>> my(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String type) {
        return Result.success(messageService.my(page, size, type));
    }

    @SaCheckRole("ADMIN")
    @GetMapping
    public Result<PageResult<MsgItem>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) Long userId,
                                            @RequestParam(required = false) String type) {
        return Result.success(messageService.page(page, size, userId, type));
    }
}
