package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.Dict;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 蹲守工具（到货提醒）：工具被他人占用时用户可以登记，工具恢复可用时站内提醒。
 * <p>不新增表，使用 Redis：
 * <ul>
 *   <li>{@code watch:tool:{toolId}}：Set，蹲守该工具的用户 id</li>
 *   <li>{@code watch:user:{userId}}：Set，该用户蹲守的工具 id</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolWatchService {

    private static final String KEY_TOOL = "watch:tool:";
    private static final String KEY_USER = "watch:user:";

    private final StringRedisTemplate redis;
    private final ToolMapper toolMapper;
    private final MessageService messageService;

    public void watch(Long userId, Long toolId) {
        Tool tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        if ("AVAILABLE".equals(tool.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "该工具当前可租，无需蹲守");
        }
        try {
            redis.opsForSet().add(KEY_TOOL + toolId, String.valueOf(userId));
            redis.opsForSet().add(KEY_USER + userId, String.valueOf(toolId));
        } catch (Exception e) {
            log.warn("登记蹲守失败 userId={} toolId={}: {}", userId, toolId, e.getMessage());
            throw new BizException(ResultCode.BAD_REQUEST, "蹲守登记失败，请稍后重试");
        }
    }

    public void unwatch(Long userId, Long toolId) {
        try {
            redis.opsForSet().remove(KEY_TOOL + toolId, String.valueOf(userId));
            redis.opsForSet().remove(KEY_USER + userId, String.valueOf(toolId));
        } catch (Exception e) {
            log.warn("取消蹲守失败 userId={} toolId={}: {}", userId, toolId, e.getMessage());
        }
    }

    public boolean isWatched(Long userId, Long toolId) {
        try {
            return Boolean.TRUE.equals(redis.opsForSet().isMember(KEY_TOOL + toolId, String.valueOf(userId)));
        } catch (Exception e) {
            return false;
        }
    }

    /** 我蹲守的工具列表（按当前状态展示） */
    public List<ToolWatchVO> watchedList(Long userId) {
        try {
            Set<String> ids = redis.opsForSet().members(KEY_USER + userId);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            return ids.stream().map(this::parseId).filter(id -> id != null)
                    .map(toolMapper::selectById).filter(tool -> tool != null)
                    .map(this::toVO).toList();
        } catch (Exception e) {
            log.warn("读取蹲守列表失败 userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 工具恢复可用时通知所有蹲守用户，并清理登记。
     */
    public void notifyAvailable(Long toolId, String toolName) {
        String key = KEY_TOOL + toolId;
        Set<String> users;
        try {
            users = redis.opsForSet().members(key);
        } catch (Exception e) {
            log.warn("读取蹲守用户失败 toolId={}: {}", toolId, e.getMessage());
            return;
        }
        if (users == null || users.isEmpty()) {
            return;
        }
        for (String uid : users) {
            try {
                messageService.notifyUser(Long.valueOf(uid), "TOOL_AVAILABLE", "蹲守工具已可租",
                        "你蹲守的《" + toolName + "》已重新上架，快去预约吧。");
                redis.opsForSet().remove(KEY_USER + uid, String.valueOf(toolId));
            } catch (Exception e) {
                log.warn("蹲守通知失败 userId={} toolId={}: {}", uid, toolId, e.getMessage());
            }
        }
        try {
            redis.delete(key);
        } catch (Exception ignored) {
            // 下轮工具可用时若仍有残留，会在通知后清理
        }
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ToolWatchVO toVO(Tool tool) {
        ToolWatchVO vo = new ToolWatchVO();
        vo.setToolId(tool.getId());
        vo.setCode(tool.getCode());
        vo.setName(tool.getName());
        vo.setStatus(tool.getStatus());
        vo.setStatusName(Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        vo.setDeposit(tool.getDeposit() == null ? BigDecimal.ZERO : tool.getDeposit());
        vo.setImageUrl(tool.getImageUrl());
        return vo;
    }
}
