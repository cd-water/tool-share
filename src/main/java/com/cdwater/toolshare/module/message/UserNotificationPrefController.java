package com.cdwater.toolshare.module.message;

import com.cdwater.toolshare.shared.config.StpKit;
import com.cdwater.toolshare.shared.web.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 居民本人提醒偏好。用户 ID 只取自会话，不接受请求参数，避免越权读写他人偏好。
 */
@RestController
@RequestMapping("/api/user/notification-prefs")
@RequiredArgsConstructor
public class UserNotificationPrefController {

    private final NotificationPrefService notificationPrefService;

    @GetMapping
    public Result<List<NotificationPrefVO>> list() {
        return Result.success(notificationPrefService.list(StpKit.USER.getLoginIdAsLong()));
    }

    @PutMapping("/{type}")
    public Result<NotificationPrefVO> update(@PathVariable String type,
                                             @Valid @RequestBody NotificationPrefUpdateDTO dto) {
        return Result.success(notificationPrefService.update(
                StpKit.USER.getLoginIdAsLong(), type, dto));
    }
}
