package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /** 两侧集合必须同时写入/删除，避免出现半套蹲守关系。 */
    private static final DefaultRedisScript<Long> ADD_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('SADD', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REMOVE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SREM', KEYS[1], ARGV[1])
            redis.call('SREM', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ToolMapper toolMapper;
    private final MessageService messageService;
    private final ToolWatchVoMapper toolWatchVoMapper;

    public void watch(Long userId, Long toolId) {
        Tool tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        if ("AVAILABLE".equals(tool.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "该工具当前可租，无需蹲守");
        }
        try {
            redis.execute(ADD_SCRIPT, List.of(KEY_TOOL + toolId, KEY_USER + userId),
                    String.valueOf(userId), String.valueOf(toolId));
        } catch (Exception e) {
            log.warn("登记蹲守失败 userId={} toolId={}: {}", userId, toolId, e.getMessage());
            throw new BizException(ResultCode.BAD_REQUEST, "蹲守登记失败，请稍后重试");
        }
    }

    public void unwatch(Long userId, Long toolId) {
        try {
            redis.execute(REMOVE_SCRIPT, List.of(KEY_TOOL + toolId, KEY_USER + userId),
                    String.valueOf(userId), String.valueOf(toolId));
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
                    .map(toolWatchVoMapper::toVO).toList();
        } catch (Exception e) {
            log.warn("读取蹲守列表失败 userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 工具恢复可用时通知所有蹲守用户，并清理登记。
     * <p>只有消息写入成功才清理两侧关系；失败保留，等待下一次可用事件按同一 biz_key 幂等重试。
     *
     * @param availableAt 本次可用事件的时间戳，用于生成幂等 biz_key
     */
    public void notifyAvailable(Long toolId, String toolName, LocalDateTime availableAt) {
        Set<String> users;
        try {
            users = redis.opsForSet().members(KEY_TOOL + toolId);
        } catch (Exception e) {
            log.warn("读取蹲守用户失败 toolId={}: {}", toolId, e.getMessage());
            return;
        }
        if (users == null || users.isEmpty()) {
            return;
        }
        long version = availableAt == null ? 0L
                : availableAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        for (String uid : users) {
            Long userId = parseId(uid);
            if (userId == null) {
                removePair(toolId, uid);
                continue;
            }
            String bizKey = "TOOL_AVAILABLE:" + toolId + ":" + userId + ":" + version;
            boolean sent = messageService.notifyUserOnce(userId, MessageType.TOOL_AVAILABLE, "蹲守工具已可租",
                    "你蹲守的《" + toolName + "》已重新上架，快去预约吧。", bizKey);
            if (!sent) {
                log.warn("蹲守通知写入失败，保留蹲守关系 userId={} toolId={}", userId, toolId);
                continue;
            }
            removePair(toolId, uid);
        }
    }

    /** 原子删除两侧关系；失败时保留，下次可用事件按 biz_key 幂等重试。 */
    private void removePair(Long toolId, String userId) {
        try {
            redis.execute(REMOVE_SCRIPT, List.of(KEY_TOOL + toolId, KEY_USER + userId),
                    userId, String.valueOf(toolId));
        } catch (Exception e) {
            log.warn("清理蹲守关系失败 userId={} toolId={}: {}", userId, toolId, e.getMessage());
        }
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
