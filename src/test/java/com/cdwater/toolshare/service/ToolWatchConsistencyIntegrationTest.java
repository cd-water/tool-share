package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.TestStorageConfig;
import com.cdwater.toolshare.module.message.MessageService;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolMapper;
import com.cdwater.toolshare.module.tool.ToolWatchService;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.enums.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * 测试设计说明
 * ------------
 * 一、为什么使用真实 Redis：
 *     蹲守关系由两个 Set 组成，分别以工具和用户为维度存储。
 *     只有连接真实 Redis，才能同时验证 key 命名、成员编码、
 *     以及两侧写入/删除是否在同一业务动作中完成。
 *
 * 二、为什么 mock 消息服务：
 *     到货通知依赖短信或站内信渠道，测试不应产生外部副作用。
 *     通过控制 notifyUserOnce 的返回值，
 *     可以稳定复现“通知失败保留关系”和“通知成功清理关系”两条分支。
 *
 * 三、关键不变量：
 *     1. 蹲守与取消蹲守必须成对维护工具侧、用户侧集合；
 *     2. 通知失败时两侧关系都保留，等待后续重试；
 *     3. 通知成功后两侧关系都清理，避免重复提醒；
 *     4. 关系清理后再次触发通知不会再发送消息。
 *
 * 四、数据隔离策略：
 *     每个用例创建独立工具，使用固定但非种子的用户 ID；
 *     AfterEach 同时清理 Redis 双侧 key 与数据库工具记录。
 */
/**
 * 工具蹲守关系一致性集成测试。
 * <p>
 * 测试范围：工具侧与用户侧两个 Redis 集合的同步维护，以及到货通知后的清理。
 * 依赖环境：随机端口完整应用、测试数据库、真实 Redis；消息服务用 MockitoBean 替换。
 * 关键不变量：蹲守与取消蹲守必须同时更新两侧集合；
 * 通知失败时两侧关系都保留以便重试；通知成功后才清理，且重复触发不重复通知。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestStorageConfig.class)
class ToolWatchConsistencyIntegrationTest extends AbstractIntegrationTest {

    // 测试数据命名空间：工具名称与工具编码都带此前缀。
    // 清理阶段按名称前缀定位本类创建的工具，
    // 同时避免与种子数据或其他集成测试的数据相互污染。
    private static final String PREFIX = "IT-WATCH-";
    /** 与种子用户隔离的测试用户 ID，避免影响真实蹲守数据。 */
    private static final long USER_ID = 9_800_001L;

    // 消息服务替换为 Mockito 替身，原因有二：
    // 1. 通知结果由用例显式控制，可分别模拟失败与成功；
    // 2. 不真正写入消息表，断言只聚焦 Redis 中的蹲守关系。
    /** 消息服务用 MockitoBean 替换，精确控制通知成功与否。 */
    @MockitoBean
    MessageService messageService;
    // 被测对象：蹲守服务。
    // 用例通过 watch/unwatch/notifyAvailable 观察对外行为，
    // 不从内部实现推断结果。
    /** 被测蹲守服务。 */
    @Autowired
    ToolWatchService watchService;
    // 工具 Mapper 用于准备一台维修中的真实工具。
    // 蹲守与到货通知都以持久化工具为输入，
    // 因此不伪造 Tool 对象，保持数据链路真实。
    /** 工具 Mapper，用于准备与清理测试工具。 */
    @Autowired
    ToolMapper toolMapper;
    // 直接读取 Redis 原始集合。
    // 两侧集合的成员关系是本测试的关键不变量，
    // 绕开服务层读取可避免用实现验证实现。
    /** 真实 Redis 客户端，用于断言两侧集合状态。 */
    @Autowired
    StringRedisTemplate redis;

    // 当前用例共享的测试工具，由 setUp 创建、cleanUp 删除。
    // 状态固定为 MAINTENANCE，模拟等待配件、后续到货的场景。
    /** 当前用例的测试工具。 */
    Tool tool;

    // 前置数据准备原则：
    // - 每个用例使用独立工具，避免用例之间通过同一工具耦合；
    // - 工具编码带纳秒后缀，重复运行也不会撞唯一键；
    // - 状态设为 MAINTENANCE，使 notifyAvailable 的业务语义成立；
    // - 分类、库位等字段按数据库约束填写，但与蹲守逻辑无关。
    /**
     * 每个用例前插入一台维修中的测试工具，
     * 使到货通知场景具备真实工具名称与状态。
     */
    @BeforeEach
    void setUp() {
        // 构造一台最小可持久化的工具记录。
        // 只填写 NOT NULL 或业务必需的字段，
        // 其余字段使用空串，减少与蹲守逻辑无关的噪声。
        tool = new Tool();
        tool.setCode("SQ-G-TW-" + System.nanoTime() % 1_000_000);
        tool.setName(PREFIX + "tool");
        tool.setModel("");
        tool.setCategory("WX");
        tool.setDamageLevel("NEW");
        tool.setStatus(ToolStatus.MAINTENANCE.code());
        // 押金设为 100 元，模拟真实工具至少有基础价值；
        // 型号、库位、图片、供应商不是蹲守关注点，
        // 但仍按约束填写，保证插入成功。
        tool.setDeposit(new BigDecimal("100"));
        tool.setStorageArea("");
        tool.setImageUrl("");
        tool.setImageUrls("");
        tool.setSupplier("");
        tool.setAuditStatus(1);
        tool.setRentalCount(0);
        // 落库后取得真实自增 ID，
        // 后续 Redis key 与通知参数都依赖该 ID。
        toolMapper.insert(tool);
    }

    // 清理顺序说明：
    // Redis 中的双侧关系先删除，再删除数据库工具记录；
    // 若先删工具，残留的 Redis key 将难以定位，
    // 可能让下一个用例读到历史蹲守数据。
    /**
     * 用例结束后删除工具侧、用户侧蹲守集合与测试工具，
     * 防止 Redis 关系残留影响后续用例。
     */
    @AfterEach
    void cleanUp() {
        // 删除工具侧与用户侧两个集合，缺一不可：
        // 工具侧维护“谁蹲守了这台工具”，
        // 用户侧维护“该用户蹲守了哪些工具”，
        // 两侧同时清空才能保证用例间彻底隔离。
        redis.delete("watch:tool:" + tool.getId());
        redis.delete("watch:user:" + USER_ID);
        toolMapper.deleteById(tool.getId());
    }

    // 该用例覆盖蹲守关系的建立与解除，是后续通知用例的基础。
    // 关注点不是通知内容，而是两个 Set 是否始终成对更新：
    // 只写一侧会造成“用户看到蹲守成功但到货不通知”，
    // 或“工具侧残留导致重复通知”的不一致。
    // 验证方式：
    // - 建立后分别从两侧集合读取成员；
    // - 取消后再分别确认成员已消失；
    // - 不做任何 mock，确保真实服务与真实 Redis 参与。
    /**
     * 场景：用户蹲守工具后取消蹲守。
     * 前置条件：工具与用户均为测试专用 ID。
     * 断言目标：蹲守后两侧集合互相包含对方成员；
     * 取消后两侧同时移除，不出现只清理一侧的脏关系。
     */
    @Test
    void watch_and_unwatch_keep_both_sets_in_sync() {
        // 两个 Redis key 分别对应关系两侧：
        // watch:tool:{toolId} 的成员是用户 ID，
        // watch:user:{userId} 的成员是工具 ID。
        String toolKey = "watch:tool:" + tool.getId();
        String userKey = "watch:user:" + USER_ID;

        // 第一步：建立蹲守关系。
        // 业务上等价于用户点击“到货提醒”，
        // 预期服务在同一业务动作中写入两侧集合。
        // 建立双侧蹲守关系，作为后续通知与清理的前置条件。
        watchService.watch(USER_ID, tool.getId());
        // 双侧同时断言，避免只验证一侧而放过另一侧的写入遗漏。
        assertTrue(isMember(toolKey, String.valueOf(USER_ID)));
        assertTrue(isMember(userKey, String.valueOf(tool.getId())));

        // 第二步：取消蹲守。
        // 取消必须与建立对称，两侧都要移除对应成员。
        watchService.unwatch(USER_ID, tool.getId());
        // 任一侧仍存在成员都说明取消逻辑不完整，
        // 会造成用户已取消却仍收到提醒的体验问题。
        assertFalse(isMember(toolKey, String.valueOf(USER_ID)));
        assertFalse(isMember(userKey, String.valueOf(tool.getId())));
    }

    // 该用例关注通知失败与成功两种分支下关系的去留，
    // 以及清理完成后的幂等性。
    // 失败时保留关系是重试的前提：
    // 若失败也清理，用户将永远收不到到货提醒。
    // 成功后清空关系是避免重复打扰的前提：
    // 若成功不清理，下一次到货会重复通知同一用户。
    // 验证方式：通过 mock 的返回值切换失败/成功，
    // 再用 Redis 成员断言关系是否保留或清除。
    /**
     * 场景：工具到货时分别模拟通知失败与成功。
     * 前置条件：消息服务被 mock 为先返回 false 再返回 true。
     * 断言目标：失败时两侧蹲守关系保留以便重试；
     * 成功后两侧清空；再次触发不会重复通知，验证幂等清理。
     */
    @Test
    void failed_notification_keeps_relation_and_success_cleans_both_sides() {
        // 固定到货时间，使通知文案的生成结果可复现；
        // 时间本身不参与 Redis 断言，只作为服务入参使用。
        String toolKey = "watch:tool:" + tool.getId();
        String userKey = "watch:user:" + USER_ID;
        LocalDateTime availableAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        // 先建立蹲守关系，否则后续通知没有接收者，
        // 也无法观察失败时关系是否被误删。
        watchService.watch(USER_ID, tool.getId());

        // 模拟第一次通知失败：
        // notifyUserOnce 返回 false，代表短信或站内信未成功送达。
        when(messageService.notifyUserOnce(anyLong(), any(MessageType.class), anyString(), anyString(), anyString()))
                .thenReturn(false);
        // 触发到货通知。
        // 此时服务应识别到通知失败，并保留蹲守关系供后续重试。
        watchService.notifyAvailable(tool.getId(), tool.getName(), availableAt);
        // 失败分支的核心断言：两侧关系都必须保留。
        // 若只保留一侧，重试时可能找不到用户或找不到工具。
        assertTrue(isMember(toolKey, String.valueOf(USER_ID)), "通知失败时不能丢蹲守关系");
        assertTrue(isMember(userKey, String.valueOf(tool.getId())), "通知失败时不能丢蹲守关系");

        // 模拟第二次通知成功：
        // 同一个 mock 改为返回 true，代表渠道已恢复正常。
        when(messageService.notifyUserOnce(anyLong(), any(MessageType.class), anyString(), anyString(), anyString()))
                .thenReturn(true);
        // 再次触发到货通知，服务应完成通知并清理关系。
        watchService.notifyAvailable(tool.getId(), tool.getName(), availableAt);
        // 成功分支的核心断言：通知完成后两侧集合都不应再有成员，
        // 否则用户下次到货会收到重复提醒。
        assertFalse(isMember(toolKey, String.valueOf(USER_ID)));
        assertFalse(isMember(userKey, String.valueOf(tool.getId())));

        // 已清理后再次触发不应重复通知
        // 关系已清理后再次触发到货通知。
        // 此时没有蹲守用户，服务不应再发送消息。
        watchService.notifyAvailable(tool.getId(), tool.getName(), availableAt);
        // 验证总调用次数仍为 2：
        // 第一次失败、第二次成功各一次，
        // 第三次因关系已清空而被跳过，证明清理具备幂等效果。
        verify(messageService, times(2)).notifyUserOnce(eq(USER_ID), eq(MessageType.TOOL_AVAILABLE),
                anyString(), anyString(), anyString());
    }

    // 辅助方法：封装 Redis Set 成员判断。
    // 统一处理 Boolean 可能为 null 的情况，
    // 避免测试因未初始化的 key 抛出空指针。
    // 只读不写，不改变被测状态。
    /** 辅助方法：判断成员是否存在于指定 Redis 集合。 */
    private boolean isMember(String key, String member) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(key, member));
    }
}
