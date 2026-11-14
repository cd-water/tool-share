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
 * <p>接口只暴露可配置的消息类型；系统消息不在列表中，也无法通过
 * <p>系统消息不在列表中，也无法通过该控制器修改。
 * <p>更新接口按路径参数定位类型，请求体只包含开关与渠道。
 * <p>列表接口会补齐未保存的类型，因此前端不需要处理「缺失记录」分支。
 * <p>所有接口只操作当前会话用户的数据，不存在跨用户读写入口。
 * 更新接口关闭。更新成功后返回最新偏好，前端可直接替换本地状态。
 * 居民本人提醒偏好。用户 ID 只取自会话，不接受请求参数，避免越权读写他人偏好。
 */
@RestController
@RequestMapping("/api/user/notification-prefs")
@RequiredArgsConstructor
public class UserNotificationPrefController {
    // 用户 ID 只取自会话。
    // 列表会补齐未保存的类型。
    // 系统消息不可关闭。
    // 更新按路径参数定位消息类型。

    /** 居民提醒偏好服务。 */
    private final NotificationPrefService notificationPrefService;

    /**
     * 查询当前居民的全部提醒偏好。
     * <p>未单独配置的类型也会返回默认值，因此结果始终覆盖所有可配置类型。
     *
     * @return 提醒偏好列表
     * <p>未保存过偏好的类型也会返回默认渠道与开启状态。
     */
    @GetMapping
    public Result<List<NotificationPrefVO>> list() {
        return Result.success(notificationPrefService.list(StpKit.USER.getLoginIdAsLong()));
    }

    /**
     * 更新当前居民某个消息类型的提醒偏好。
     * <p>消息类型来自路径参数，用户 ID 来自会话；系统消息不可关闭。
     *
     * @param type 消息类型编码
     * @param dto  是否开启与投递渠道
     * @return 更新后的偏好
     * <p>系统消息与未知类型都会被拒绝。
     */
    @PutMapping("/{type}")
    public Result<NotificationPrefVO> update(@PathVariable String type,
                                             @Valid @RequestBody NotificationPrefUpdateDTO dto) {
        return Result.success(notificationPrefService.update(
                StpKit.USER.getLoginIdAsLong(), type, dto));
    }
}
