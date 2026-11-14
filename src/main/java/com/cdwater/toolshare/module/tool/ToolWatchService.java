/**
 * 工具蹲守（到货提醒）文件。
 *
 * <h2>数据模型</h2>
 * <p>不新增表，使用两个 Redis Set 维护双向关系：
 * watch:tool:{toolId} -> 用户 id 集合；watch:user:{userId} -> 工具 id 集合。
 * 写入与删除都通过 Lua 脚本原子执行，避免只写一侧造成半套关系。
 *
 * <h2>业务规则</h2>
 * <p>只有不可用的工具可以蹲守；工具恢复可用时逐个通知并清理关系。
 * 通知幂等键包含工具、用户与本次可用事件时间戳，同一次事件不会重复发送。
 * 只有消息写入成功才清理关系；失败保留，等待下一次可用事件重试。
 *
  * <h2>Lua 脚本的作用</h2>
  * <p>登记与取消都必须在两个集合上同时生效。
  * 普通两次 SADD/SREM 在异常或并发下可能只完成一半，
  * 所以使用 Lua 脚本在 Redis 单线程内原子执行两侧操作。
  *
  * <h2>通知循环的边界</h2>
  * <p>notifyAvailable 先读取用户集合快照，再逐个发送；
  * 发送过程中新登记的蹲守用户不会收到本次事件，会在下一次可用事件被通知。
  * 非法用户 id 直接清理，避免脏数据长期占用通知循环。
  *
  * <h2>降级行为</h2>
  * <p>登记失败向上返回可重试错误；取消、查询、列表都静默降级，
  * 因为蹲守是辅助功能，不应阻塞工具浏览与租赁主流程。
  *
      * <h2>相关文件</h2>
      * <p>ToolWatchVO/ToolWatchVoMapper（视图）、UserToolController（入口）、
      * ToolService（触发可用事件）、MessageService（站内消息）。
     * <h2>监控指标</h2>
     * <p>可观测：单工具蹲守人数、通知发送失败次数、关系清理失败次数。
    * <h2>容量与性能</h2>
    * <p>蹲守关系是双向 Set，存储量约为“蹲守关系数 × 2”；
    * 通常远小于用户数与工具数的乘积。
    * <p>通知时先读取工具侧集合，再逐用户发送消息；
    * 单次事件的通知量取决于蹲守人数，没有分页或批处理。
    * 若热门工具蹲守人数很大，应改为异步队列分批处理。
   * <h2>运维操作</h2>
   * <p>查看某工具蹲守人数：SCARD watch:tool:{toolId}；
   * 查看某用户蹲守列表：SMEMBERS watch:user:{userId}。
   * <p>两侧集合应成对存在；若发现单边残留，可按工具 id 或用户 id 手工清理。
   * 通知发送失败不会自动重试，需要等待下一次工具可用事件。
  * <h2>清理时机</h2>
  * <p>通知成功、工具再次可用、用户主动取消都会清理关系；
  * 工具不存在时不会建立新关系，但历史残留由通知循环或用户取消清理。
 * <h2>容错</h2>
 * <p>Redis 异常：登记返回可重试错误；查询/列表降级为空；
 * 通知只记日志，不影响工具状态流转。
 */
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

    // watch:tool:{toolId} -> Set<userId>，用于恢复可用时反查蹲守用户
    private static final String KEY_TOOL = "watch:tool:";
    // watch:user:{userId} -> Set<toolId>，用于“我蹲守的工具”列表
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

    /**
     * 登记蹲守关系。
     * <p>工具不存在返回 404；工具当前可用时拒绝登记（直接预约即可）。
     * 两侧集合通过 Lua 脚本原子写入；Redis 异常统一转为可重试的业务错误。
     *
     * @param userId 用户 id
     * @param toolId 工具 id
     * @throws BizException 工具不存在（404）、当前可租或登记失败（400）
     */
    public void watch(Long userId, Long toolId) {
        // 预留：蹲守前读取工具状态与名称
        Tool tool = toolMapper.selectById(toolId);
        // 工具不存在时不能建立蹲守关系
        if (tool == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工具不存在");
        }
        // 可用工具无需蹲守，避免产生永远等不到通知的无效关系
        // 可用工具直接预约即可，无需蹲守
        if ("AVAILABLE".equals(tool.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "该工具当前可租，无需蹲守");
        }
        try {
            // Lua 脚本一次写入两侧集合，避免半套关系
            // 原子写入工具侧与用户侧集合
            redis.execute(ADD_SCRIPT, List.of(KEY_TOOL + toolId, KEY_USER + userId),
                    String.valueOf(userId), String.valueOf(toolId));
        } catch (Exception e) {
            // 登记失败转为可重试错误
            log.warn("登记蹲守失败 userId={} toolId={}: {}", userId, toolId, e.getMessage());
            // Redis 异常转为可重试的业务错误，不暴露底层细节
            throw new BizException(ResultCode.BAD_REQUEST, "蹲守登记失败，请稍后重试");
        }
    }

    /**
     * 取消蹲守关系。
     * <p>幂等：关系不存在也视为成功；Redis 异常只记日志，不向上抛错。
     *
     * @param userId 用户 id
     * @param toolId 工具 id
     */
    public void unwatch(Long userId, Long toolId) {
        try {
            // 取消蹲守同样原子删除两侧
            redis.execute(REMOVE_SCRIPT, List.of(KEY_TOOL + toolId, KEY_USER + userId),
                    String.valueOf(userId), String.valueOf(toolId));
        } catch (Exception e) {
            // 取消失败只记录，用户可重试
            log.warn("取消蹲守失败 userId={} toolId={}: {}", userId, toolId, e.getMessage());
        }
    }

    /**
     * 判断用户是否蹲守某工具。
     * <p>Redis 不可用时返回 false，让前端降级为未蹲守而不是报错。
     *
     * @param userId 用户 id
     * @param toolId 工具 id
     * @return 已蹲守返回 true
     */
    public boolean isWatched(Long userId, Long toolId) {
        try {
            // 只查工具侧集合即可判断用户是否蹲守
            // 只查工具侧集合即可判断
            return Boolean.TRUE.equals(redis.opsForSet().isMember(KEY_TOOL + toolId, String.valueOf(userId)));
        } catch (Exception e) {
            return false;
        }
    }

    /** 我蹲守的工具列表（按当前状态展示） */
    /**
     * 查询用户蹲守的工具列表，状态以数据库当前值为准。
     * <p>Redis 异常返回空列表；已删除的工具会被自然过滤。
     *
     * @param userId 用户 id
     * @return 蹲守工具视图列表
     */
    public List<ToolWatchVO> watchedList(Long userId) {
        try {
            // 从用户侧集合取出蹲守的工具 id
            // 用户侧集合给出蹲守的工具 id
            Set<String> ids = redis.opsForSet().members(KEY_USER + userId);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            // 过滤脏数据并批量读取工具当前状态
            // 过滤非法 id，读取工具当前状态
            return ids.stream().map(this::parseId).filter(id -> id != null)
                    .map(toolMapper::selectById).filter(tool -> tool != null)
                    .map(toolWatchVoMapper::toVO).toList();
        } catch (Exception e) {
            // 列表读取失败降级为空
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
    /**
     * 工具恢复可用时通知全部蹲守用户并清理关系。
     * <p>幂等策略：biz_key 包含工具、用户与本次可用事件时间戳，
     * 同一次可用事件重复处理不会产生重复消息。
     * 只有消息确认写入成功才清理关系；失败保留，等待下一次可用事件重试。
     *
     * @param toolId      工具 id
     * @param toolName    工具名称，用于消息文案
     * @param availableAt 本次可用事件时间，用于生成幂等版本
     */
    public void notifyAvailable(Long toolId, String toolName, LocalDateTime availableAt) {
        Set<String> users;
        try {
            // 从工具侧集合取出所有蹲守用户
            // 工具侧集合给出所有蹲守用户
            users = redis.opsForSet().members(KEY_TOOL + toolId);
        } catch (Exception e) {
            // 读取失败直接结束本次通知
            log.warn("读取蹲守用户失败 toolId={}: {}", toolId, e.getMessage());
            return;
        }
        // 没有蹲守用户时无需处理
        if (users == null || users.isEmpty()) {
            return;
        }
        // 时间戳作为版本：同一恢复事件时间相同，重复通知会被 biz_key 去重
        // 可用事件时间作为幂等版本
        long version = availableAt == null ? 0L
                : availableAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        // 逐用户发送；幂等键保证同一事件不重复
        // 逐用户发送，失败保留关系
        for (String uid : users) {
            Long userId = parseId(uid);
            // 非法用户 id 直接清理关系，避免脏数据长期残留
            // 非法用户 id 直接清理
            if (userId == null) {
                removePair(toolId, uid);
                continue;
            }
            // 幂等键包含事件时间戳，同一次恢复事件只发一次
            // 同一事件同一用户只发一次
            String bizKey = "TOOL_AVAILABLE:" + toolId + ":" + userId + ":" + version;
            // 通知失败时保留蹲守关系，避免用户错过消息
            boolean sent = messageService.notifyUserOnce(userId, MessageType.TOOL_AVAILABLE, "蹲守工具已可租",
                    "你蹲守的《" + toolName + "》已重新上架，快去预约吧。", bizKey);
            // 消息未写入：保留关系，下一轮可用事件重试
            // 消息未写入则保留蹲守关系
            if (!sent) {
                log.warn("蹲守通知写入失败，保留蹲守关系 userId={} toolId={}", userId, toolId);
                continue;
            }
            // 消息成功后才清理关系
            removePair(toolId, uid);
        }
    }

    /** 原子删除两侧关系；失败时保留，下次可用事件按 biz_key 幂等重试。 */
    /**
     * 原子删除两侧蹲守关系。
     * <p>失败只记日志：关系残留会在下一次可用事件被再次尝试清理。
     *
     * @param toolId 工具 id
     * @param userId 用户 id（字符串形式，与 Redis 存储一致）
     */
    private void removePair(Long toolId, String userId) {
        try {
            // 清理关系使用独立脚本，失败只记日志
            // 取消同样原子删除两侧
            redis.execute(REMOVE_SCRIPT, List.of(KEY_TOOL + toolId, KEY_USER + userId),
                    userId, String.valueOf(toolId));
        } catch (Exception e) {
            // 清理失败保留关系，下次事件再试
            log.warn("清理蹲守关系失败 userId={} toolId={}: {}", userId, toolId, e.getMessage());
        }
    }

    /**
     * 解析 Redis 中的 id 字符串，非法值返回 null 由调用方跳过或清理。
     *
     * @param value 字符串 id
     * @return Long 值或 null
     */
    private Long parseId(String value) {
        try {
            // 非法 id 返回 null，由调用方决定跳过或清理
            // 非法 id 返回 null
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
