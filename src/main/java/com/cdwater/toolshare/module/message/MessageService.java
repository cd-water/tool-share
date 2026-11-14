package com.cdwater.toolshare.module.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.shared.enums.DeliveryStatus;
import com.cdwater.toolshare.shared.enums.MessageChannel;
import com.cdwater.toolshare.shared.enums.MessageType;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.PageQuery;
import com.cdwater.toolshare.shared.web.ResultCode;
import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminMapper;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserMapper;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageMapper;
import com.cdwater.toolshare.module.message.MessageVO;
import com.cdwater.toolshare.shared.web.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 消息中心服务：负责站内消息与短信的落库、去重、渠道选择与查询。
 * <p>核心职责：
 * <ul>
 *   <li>为居民或管理员写入消息记录，并保存投递状态；</li>
 *   <li>按居民提醒偏好决定是否发送以及使用哪个渠道；</li>
 *   <li>通过 bizKey 实现业务事件级幂等，避免定时任务或重试产生重复消息；</li>
 *   <li>提供分页、未读数、单条已读与全部已读等查询能力。</li>
 * </ul>
 * <p>渠道策略：默认站内信（SYSTEM）；居民可为每种消息类型单独选择短信。
 * 短信开关由 {@link MessageConfigService} 统一读取，关闭时自动退回站内信，
 * 保证提醒仍可触达。短信发送失败只记录 FAILED 与日志，不向上抛出异常，
 * 避免影响报修、租赁等主业务。
 * <p>投递状态：
 *   <li>PENDING：预留状态，当前实现不会主动写入；</li>
 *   <li>DELIVERED：站内信已写入，居民可在消息中心看到；</li>
 *   <li>SENT：短信已交给网关，但未模拟送达回执；</li>
 *   <li>FAILED：短信发送异常，已记录日志，消息仍保留在站内信列表中。</li>
 * <p>幂等策略：先按 bizKey 查询，再依赖数据库唯一索引兜底；并发插入
 * 触发 {@link DuplicateKeyException} 时视为成功，因为另一方已写入。
 * bizKey 建议使用「业务动作 + 业务主键」，例如 REPAIR_SUBMIT:{id}。
 * <p>返回值语义：true 表示「已按当前规则处理完毕」，包括写入成功、偏好
 * 关闭与幂等命中；false 只表示数据库写入失败，调用方可选择重试。
 * <p>可观测性：短信失败与数据库写入失败分别使用 warn 与 error 日志，
 * <p>消息中心只读，发送由业务事件触发，不提供任意广播接口。
 * 并带上接收人、消息类型与 bizKey，便于按业务事件排查。
 */
public class MessageService {

    /** 消息表访问入口，承担落库、分页与未读数统计。 */
    private final MessageMapper messageMapper;
    /** 管理员账号查询入口，用于把同一条管理通知分发给全部启用账号。 */
    private final AdminMapper adminMapper;
    /** 居民账号查询入口，短信渠道需要读取接收人手机号。 */
    private final UserMapper userMapper;
    /** 居民提醒偏好访问入口，决定是否发送以及使用哪个渠道。 */
    private final UserNotificationPrefMapper prefMapper;
    /** 短信发送适配器；当前为 Mock 实现，只输出日志。 */
    private final SmsSender smsSender;
    /** 消息实体到视图对象的映射器，补齐类型中文名与布尔已读状态。 */
    private final MessageVoMapper messageVoMapper;
    /** 消息渠道配置服务，提供默认渠道与短信开关。 */
    private final MessageConfigService configService;

    /**
     * 向单个居民发送提醒（无业务去重键）。
     * <p>适用于不要求幂等的即时通知；若同一业务事件可能重复触发，
     * 应改用 {@link #notifyUserOnce} 并传入稳定的 bizKey。
     *
     * @param userId  居民用户主键
     * @param type    消息类型，决定偏好过滤与前端分类
     * @param title   消息标题
     * @param content 消息正文
     * <p>不传 bizKey 意味着不做幂等，调用方应确保不会对同一事件重复调用。
     * @return true 表示已写入或按偏好无需发送；false 表示写入失败
     */
    public boolean notifyUser(Long userId, MessageType type, String title, String content) {
    // 返回值只表示处理结果，不暴露内部渠道细节。
    // 管理端消息与居民消息共用写入实现。
    // 短信失败不会影响站内信落库。
        // 不传 bizKey，因此不做业务幂等。
        // 居民偏好关闭时直接返回成功但不写入消息。
        // 短信失败时仍保留站内信记录。
        return insert("USER", userId, type, title, content, null);
    }

    /**
     * 向单个居民发送幂等提醒。
     * <p>bizKey 建议由「业务前缀 + 业务主键」组成。已存在相同 bizKey 时
     * 直接视为成功，不重复写入，也不重复触发短信。
     *
     * @param userId  居民用户主键
     * @param type    消息类型
     * @param title   消息标题
     * @param content 消息正文
     * @param bizKey  业务幂等键，可为空；为空时退化为普通发送
     * <p>bizKey 只需在业务范围内稳定，长度受数据库唯一索引约束。
     * @return true 表示已写入或命中幂等；false 表示写入失败
     */
    public boolean notifyUserOnce(Long userId, MessageType type, String title, String content,
                                  String bizKey) {
                                  // 带 bizKey 的发送会先做幂等判重。
                                  // 已存在相同 bizKey 时直接视为成功。
                                  // 不重复写入消息，也不重复触发短信。
        return insert("USER", userId, type, title, content, bizKey);
    }

    /**
     * 向全部启用的管理员广播一条消息。
     * <p>每个管理员生成独立的 bizKey（追加 admin:{id}），因此同一次广播
     * 对每个管理员只会送达一次。任一管理员写入失败都会使返回值为 false，
     * 但不会中断其余管理员的写入。
     *
     * @param type    消息类型
     * @param title   消息标题
     * @param content 消息正文
     * @param bizKey  广播级幂等键，可为空
     * <p>某个管理员写入失败不会中断后续管理员。
     * @return 全部管理员都成功（或被幂等跳过）时为 true
     * <p>没有启用管理员时循环不执行，返回 true。
     * <p>广播级 bizKey 会为每个管理员派生独立幂等键。
     * <p>某个管理员写入失败不会中断后续管理员，返回值汇总整体结果。
     */
    public boolean notifyAdmins(MessageType type, String title, String content, String bizKey) {
    // 管理员列表只读取启用账号。
    // 每个账号独立幂等，互不影响。
    // 返回值为整体成功标记。
        // 只向启用状态的管理员广播。
        // 每个管理员使用独立的 bizKey。
        // 任一失败不中断后续管理员，结果汇总返回。
        // 只向启用状态的管理员广播，停用账号不再接收新消息。
        List<Admin> admins = adminMapper.selectList(
                new LambdaQueryWrapper<Admin>().eq(Admin::getStatus, 1));
        // 使用逻辑与汇总结果：任一管理员写入失败都会把整体结果置为 false。
        boolean success = true;
        // 逐个管理员写入，单个失败不影响其余人接收。
        for (Admin admin : admins) {
            // 广播级 bizKey 追加管理员 ID，形成每个管理员独立的幂等键。
            String key = bizKey == null ? null : bizKey + ":admin:" + admin.getId();
            // 使用 &= 而非短路：确保所有管理员都会被尝试写入。
            success &= insert("ADMIN", admin.getId(), type, title, content, key);
        }
        // 汇总结果供调用方决定是否重试整批广播。
        return success;
    }

    /**
     * 消息写入的统一实现。
     * <p>处理顺序：确定渠道 → 居民偏好过滤 → bizKey 判重 → 可选短信发送 →
     * 落库。任何阶段失败都不会抛给调用方，而是通过返回值或 FAILED 状态体现，
     * 以保证报修、租赁等主流程不被消息系统拖垮。
     *
     * @param receiverType USER 或 ADMIN
     * @param receiverId   接收人主键
     * @param messageType  消息类型
     * @param title        标题
     * @param content      正文
     * @param bizKey       幂等键，可为空
     * <p>该方法不抛异常，所有失败都通过返回值与日志体现。
     * <p>偏好关闭与幂等命中不会写入新记录，但返回成功。
     * <p>短信渠道失败时仍会写入一条 FAILED 的站内消息记录。
     * @return 是否视为成功
     * <p>返回 false 仅表示数据库写入失败；偏好关闭与幂等命中都返回 true。
     */
    private boolean insert(String receiverType, Long receiverId, MessageType messageType,
                           String title, String content, String bizKey) {
                           // 先确定渠道，再读取居民偏好。
                           // 短信失败时仍保留 FAILED 的站内消息记录。
                           // 数据库写入异常只返回 false，不向上抛出。
        // 消息类型以编码落库，避免枚举顺序变化影响历史数据。
        String type = messageType.code();
        // 默认站内信；只有居民且非系统消息才会考虑个性化渠道。
        String channel = MessageChannel.SYSTEM.code();
        // 居民提醒先读偏好：关闭的类型不发送，渠道按偏好或数据库配置的默认渠道
        // 系统公告不可关闭，因此不查询偏好，始终按站内信发送。
        if ("USER".equals(receiverType) && messageType != MessageType.SYSTEM) {
            UserNotificationPref pref = prefMapper.selectOne(
                    new LambdaQueryWrapper<UserNotificationPref>()
                            .eq(UserNotificationPref::getUserId, receiverId)
                            .eq(UserNotificationPref::getMessageType, type));
            // 居民显式关闭该类型提醒时直接返回成功：业务方无需感知「被偏好拦截」。
            if (pref != null && pref.getEnabled() != null && pref.getEnabled() == 0) {
                return true;
            }
            // 未配置偏好时使用管理端默认渠道；非法渠道编码回退站内信。
            channel = MessageChannel.fromCode(pref == null
                            ? configService.defaultChannel() : pref.getChannel())
                    .orElse(MessageChannel.SYSTEM).code();
        }

        // 先按 biz_key 判重，避免 Mock 短信在重复调度时被再次发送
        // 先查一次可避免无意义的短信发送；唯一索引仍负责并发窗口。
        if (bizKey != null && messageMapper.selectCount(
                new LambdaQueryWrapper<Message>().eq(Message::getBizKey, bizKey)) > 0) {
            return true;
        }

        // 站内信写入即视为「已送达」；短信渠道稍后覆盖该状态。
        DeliveryStatus deliveryStatus = DeliveryStatus.DELIVERED;
        LocalDateTime sentTime = LocalDateTime.now();
        // 仅短信渠道需要调用外部适配器并处理失败。
        if (MessageChannel.SMS.code().equals(channel)) {
            // 管理端关闭短信时退回站内信，保证居民仍能看到提醒。
            if (!configService.smsEnabled()) {
                // 未启用短信通道时退回站内消息，保证提醒仍可触达
                channel = MessageChannel.SYSTEM.code();
            } else {
                try {
                    // 短信必须有手机号；账号缺失或号码为空时按发送失败处理。
                    User user = userMapper.selectById(receiverId);
                    if (user == null || user.getPhone() == null || user.getPhone().isBlank()) {
                        throw new IllegalStateException("接收人手机号不存在");
                    }
                    // 标题与正文拼接为一条短信，适配器负责实际发送或 Mock 日志。
                    smsSender.send(user.getPhone(), title + "：" + content);
                    // Mock 只确认已交给通道，不模拟送达回执
                    // Mock 只确认消息已交给通道，不模拟运营商送达回执。
                    deliveryStatus = DeliveryStatus.SENT;
                } catch (Exception e) {
                    // 短信失败只记录日志并标记 FAILED，不中断消息落库。
                    log.warn("短信发送失败 receiverId={} type={}: {}", receiverId, type, e.getMessage());
                    // 发送异常时标记失败且不记录发送时间，便于后续排查与补偿。
                    deliveryStatus = DeliveryStatus.FAILED;
                    sentTime = null;
                }
            }
        }

        // 无论短信成功与否都落一条消息记录，居民端可查看完整历史。
        Message message = new Message();
        // 接收人类型与 ID 共同构成消息归属，查询时两者都必须匹配。
        message.setReceiverType(receiverType);
        // 接收人 ID；居民消息为 userId，管理消息为 adminId。
        message.setReceiverId(receiverId);
        // 业务类型用于前端分类与居民偏好匹配。
        message.setType(type);
        // 标题用于列表展示与短信前缀。
        message.setTitle(title);
        // 正文为完整通知内容，站内信与短信共用。
        message.setContent(content);
        // 最终实际使用的渠道；可能已从 SMS 回退为 SYSTEM。
        message.setChannel(channel);
        // 投递状态独立于已读状态：送达不代表已读。
        message.setDeliveryStatus(deliveryStatus.code());
        // 站内信为写入时间；短信失败时置空，表示未真正发出。
        message.setSentTime(sentTime);
        // 新消息默认未读，未读数是首页角标的来源。
        message.setReadStatus(0);
        // bizKey 由数据库唯一索引约束，是跨进程幂等的最终保障。
        message.setBizKey(bizKey);
        try {
            // 正常写入后返回成功；后续异常由下方 catch 统一处理。
            messageMapper.insert(message);
            return true;
        // 并发下另一请求已写入同一 bizKey，本请求视为成功即可。
        } catch (DuplicateKeyException ignored) {
            // 定时提醒按 biz_key 去重；并发下另一方已写入，视为成功
            return true;
        } catch (Exception e) {
            // 数据库写入失败打印完整上下文，便于排查唯一索引或连接问题。
            log.error("消息写入失败 receiverType={} receiverId={} type={} bizKey={}",
                    receiverType, receiverId, type, bizKey, e);
            return false;
        }
    }

    /**
     * 分页查询某个接收人的消息，按创建时间倒序。
     * <p>接收人类型与 ID 都由调用方从会话派生，不作为用户可控参数透传。
     *
     * @param receiverType USER 或 ADMIN
     * @param receiverId   接收人主键
     * @param page         页码
     * @param size         每页条数
     * <p>只返回当前接收人的消息，排序与分页由数据库完成。
     * @return 消息分页结果
     * <p>消息按创建时间倒序，最新消息在最前。
     * <p>列表只返回当前接收人的消息，不提供跨用户查询入口。
     * <p>分页参数由 PageQuery 统一钳制，避免超大分页。
     * <p>消息内容在发送时已经确定，列表查询不需要额外拼装。
     * <p>列表只读取消息表，不联表查询接收人信息，保证分页稳定。
     */
    public PageVO<MessageVO> page(String receiverType, Long receiverId, long page, long size) {
    // 查询条件严格限定接收人类型与 ID。
    // 分页由数据库完成。
    // 列表按创建时间倒序。
        // 接收人类型与 ID 共同构成查询条件。
        // 按创建时间倒序分页。
        // 视图映射补齐类型名称与布尔已读状态。
        // 接收人类型与 ID 都在 SQL 条件中，天然隔离不同用户的消息。
        Page<Message> result = messageMapper.selectPage(PageQuery.of(page, size),
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getReceiverType, receiverType)
                        .eq(Message::getReceiverId, receiverId)
                        .orderByDesc(Message::getCreateTime));
        // 逐条转换视图对象，补齐类型名称与布尔已读状态。
        return PageVO.of(result, messageVoMapper::toVO);
    }

    /**
     * 统计某个接收人的未读消息数，用于导航栏角标。
     *
     * @param receiverType USER 或 ADMIN
     * @param receiverId   接收人主键
     * <p>统计条件与列表一致，避免角标数量与列表内容不一致。
     * @return 未读消息数量
     * <p>统计只读取消息表，不联表查询接收人。
     * <p>未读数随已读操作实时变化，不需要额外缓存。
     * <p>角标只关心是否已读，不区分消息类型与渠道。
     * <p>短信发送失败的消息仍会在站内信列表中显示为未读。
     * <p>未读数只统计 read_status=0，不区分渠道与投递状态。
     */
    public long unreadCount(String receiverType, Long receiverId) {
    // 只统计未读状态。
    // 不返回消息内容，适合频繁轮询角标。
    // 条件与列表一致。
        // 只统计 read_status=0 的消息。
        // 条件与列表查询保持一致。
        // 不区分渠道与投递状态。
        // 统计当前接收人 read_status=0 的消息数量。
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverType, receiverType)
                .eq(Message::getReceiverId, receiverId)
                // 0 表示未读；1 表示已读。
                .eq(Message::getReadStatus, 0));
    }

    /**
     * 将指定消息标记为已读。
     * <p>必须同时校验接收人类型与 ID，防止通过猜测消息 ID 越权修改他人消息。
     *
     * @param receiverType USER 或 ADMIN
     * @param receiverId   接收人主键
     * @param id           消息主键
     * <p>已读状态可重复设置，重复调用不会产生额外副作用。
     * @throws BizException 消息不存在或不属于该接收人时抛出
     * <p>更新使用实体更新而非条件更新，归属校验已在前面完成。
     */
    public void markRead(String receiverType, Long receiverId, Long id) {
    // 先校验归属，再更新已读状态。
    // 不存在与越权统一返回 404。
    // 重复调用不会报错。
        // 先按主键读取消息。
        // 接收人类型或 ID 不匹配时返回 404。
        // 已读状态可重复设置。
        Message message = messageMapper.selectById(id);
        // 归属不匹配时统一返回 404，避免泄露消息是否存在。
        if (message == null || !receiverType.equals(message.getReceiverType())
                || !receiverId.equals(message.getReceiverId())) {
            // 归属不匹配与不存在使用同一响应，避免探测他人消息。
            throw new BizException(ResultCode.NOT_FOUND, "消息不存在");
        }
        // 已读只需更新单个字段，其他内容保持不变。
        message.setReadStatus(1);
        // 按主键更新已读状态。
        messageMapper.updateById(message);
    }

    /**
     * <p>没有未读消息时更新行数为 0，仍然视为成功。
     * 将某个接收人的全部未读消息标记为已读。
     * <p>使用单条 UPDATE 完成，避免逐条查询与更新。
     * <p>批量更新不返回具体行数，前端只需刷新角标。
     *
     * @param receiverType USER 或 ADMIN
     * @param receiverId   接收人主键
     */
    public void markAllRead(String receiverType, Long receiverId) {
        // 单条 UPDATE 批量清零未读。
        // 只作用于当前接收人。
        // 没有未读消息时更新行数为 0，仍视为成功。
        // 单条 UPDATE 批量清零未读，避免逐条查询。
        messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .eq(Message::getReceiverType, receiverType)
                .eq(Message::getReceiverId, receiverId)
                .eq(Message::getReadStatus, 0)
                // 只更新当前接收人的未读消息，不影响他人。
                .set(Message::getReadStatus, 1));
    }

}
