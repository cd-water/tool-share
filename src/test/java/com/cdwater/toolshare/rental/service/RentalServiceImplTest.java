package com.cdwater.toolshare.rental.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.config.NotifyMessage;
import com.cdwater.toolshare.config.RabbitMqConfig;
import com.cdwater.toolshare.rental.dto.RentalCreateDTO;
import com.cdwater.toolshare.rental.dto.RentalPickupDTO;
import com.cdwater.toolshare.rental.dto.RentalQueryDTO;
import com.cdwater.toolshare.rental.dto.RentalReturnDTO;
import com.cdwater.toolshare.rental.entity.RentalOrder;
import com.cdwater.toolshare.rental.mapper.RentalOrderMapper;
import com.cdwater.toolshare.rental.service.impl.RentalServiceImpl;
import com.cdwater.toolshare.rental.vo.RentalCreateVO;
import com.cdwater.toolshare.rental.vo.RentalOrderVO;
import com.cdwater.toolshare.rental.vo.TimeSlotVO;
import com.cdwater.toolshare.tool.dto.ToolStatusDTO;
import com.cdwater.toolshare.tool.entity.Tool;
import com.cdwater.toolshare.tool.service.ToolService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 租赁业务单元测试（Mock 掉 Mapper、Redis 锁、MQ 与登录上下文）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RentalServiceImplTest {

    private static final long USER_ID = 99L;
    private static final long ORDER_ID = 5L;
    private static final long TOOL_ID = 1L;

    @Mock
    private RentalOrderMapper rentalOrderMapper;

    @Mock
    private ToolService toolService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private com.cdwater.toolshare.common.util.SerialNoGenerator serialNoGenerator;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private RentalServiceImpl rentalService;

    private MockedStatic<StpUtil> stp;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // LambdaQueryWrapper 依赖 MyBatis-Plus 的实体元数据缓存，纯单元测试需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RentalOrder.class);
    }

    @BeforeEach
    void setUp() {
        rentalService = new RentalServiceImpl(rentalOrderMapper, toolService, redissonClient,
                serialNoGenerator, rabbitTemplate);
        stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        stp.close();
    }

    // ------------------------------------------------------------------
    // 可预约时段
    // ------------------------------------------------------------------

    @Test
    void availableTimes_returnsEmptyWhenToolNotAvailable() {
        when(toolService.detail(TOOL_ID)).thenReturn(tool(2));

        assertTrue(rentalService.availableTimes(TOOL_ID, LocalDate.now().plusDays(1)).isEmpty());
        verifyNoInteractions(rentalOrderMapper);
    }

    @Test
    void availableTimes_generatesHourlySlotsAndSkipsOccupied() {
        LocalDate day = LocalDate.now().plusDays(1);
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));
        when(rentalOrderMapper.selectList(any())).thenReturn(List.of(
                order(ORDER_ID, USER_ID, 2, day.atTime(10, 0), day.atTime(12, 0))));

        List<TimeSlotVO> slots = rentalService.availableTimes(TOOL_ID, day);

        // 09:00-18:00 共 9 个整点时段，占用 10:00-12:00 后剩 7 个
        assertEquals(7, slots.size());
        assertTrue(slots.stream().noneMatch(s -> s.getStartTime().equals(day.atTime(10, 0))));
        assertTrue(slots.stream().noneMatch(s -> s.getStartTime().equals(day.atTime(11, 0))));
        assertEquals(day.atTime(9, 0), slots.getFirst().getStartTime());
        assertEquals(day.atTime(18, 0), slots.getLast().getEndTime());
    }

    // ------------------------------------------------------------------
    // 创建预约
    // ------------------------------------------------------------------

    @Test
    void create_rejectsStartTimeNotBeforeEndTime() {
        LocalDateTime start = LocalDateTime.now().plusDays(2);
        RentalCreateDTO dto = dto(TOOL_ID, start, start, "张三");

        BizException ex = assertThrows(BizException.class, () -> rentalService.create(dto));

        assertEquals("C400", ex.getCode());
        verifyNoInteractions(toolService, redissonClient, rentalOrderMapper);
    }

    @Test
    void create_rejectsPastStartTime() {
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        RentalCreateDTO dto = dto(TOOL_ID, start, start.plusHours(2), "张三");

        BizException ex = assertThrows(BizException.class, () -> rentalService.create(dto));

        assertEquals("C400", ex.getCode());
    }

    @Test
    void create_rejectsUnavailableTool() {
        RentalCreateDTO dto = futureDto();
        when(toolService.detail(TOOL_ID)).thenReturn(tool(2));

        BizException ex = assertThrows(BizException.class, () -> rentalService.create(dto));

        assertEquals("C409", ex.getCode());
        assertEquals("工具当前不可预约", ex.getMessage());
    }

    @Test
    void create_rejectsWhenLockIsBusy() throws Exception {
        RentalCreateDTO dto = futureDto();
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));
        when(redissonClient.getLock("lock:rental:tool:" + TOOL_ID)).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> rentalService.create(dto));

        assertEquals("C409", ex.getCode());
        assertEquals("预约繁忙，请稍后重试", ex.getMessage());
        verify(rentalOrderMapper, never()).insert(any(RentalOrder.class));
    }

    @Test
    void create_rejectsOverlappingReservationWithSlotHint() throws Exception {
        RentalCreateDTO dto = futureDto();
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));
        when(redissonClient.getLock("lock:rental:tool:" + TOOL_ID)).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(rentalOrderMapper.selectCount(any())).thenReturn(1L);
        when(rentalOrderMapper.selectList(any())).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> rentalService.create(dto));

        assertEquals("C409", ex.getCode());
        assertTrue(ex.getMessage().contains("该时段已被预约"));
        assertTrue(ex.getMessage().contains("最近可约时段"));
        verify(rentalOrderMapper, never()).insert(any(RentalOrder.class));
        verify(lock).unlock();
    }

    @Test
    void create_insertsPendingOrderAndPublishesPickupNotice() throws Exception {
        RentalCreateDTO dto = futureDto();
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));
        when(redissonClient.getLock("lock:rental:tool:" + TOOL_ID)).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(rentalOrderMapper.selectCount(any())).thenReturn(0L);
        when(serialNoGenerator.next("SQ-ZY-")).thenReturn("SQ-ZY-20261020-0001");
        doAnswer(inv -> {
            inv.<RentalOrder>getArgument(0).setId(7L);
            return 1;
        }).when(rentalOrderMapper).insert(any(RentalOrder.class));

        RentalCreateVO vo = rentalService.create(dto);

        assertEquals(7L, vo.getId());
        assertEquals("SQ-ZY-20261020-0001", vo.getOrderNo());

        ArgumentCaptor<RentalOrder> captor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).insert(captor.capture());
        RentalOrder saved = captor.getValue();
        assertEquals(USER_ID, saved.getUserId().longValue());
        assertEquals(1, saved.getStatus());          // 待支付
        assertEquals(1, saved.getDepositStatus());   // 未缴
        assertEquals(0, new BigDecimal("100.00").compareTo(saved.getDepositAmount()));
        assertEquals("张三", saved.getPickupName());

        verify(lock).unlock();
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.NOTIFY_DELAY_EXCHANGE),
                eq(RabbitMqConfig.NOTIFY_DELAY_ROUTING_KEY),
                any(NotifyMessage.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void create_restoresInterruptFlagWhenLockInterrupted() throws Exception {
        RentalCreateDTO dto = futureDto();
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));
        when(redissonClient.getLock("lock:rental:tool:" + TOOL_ID)).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));

        BizException ex = assertThrows(BizException.class, () -> rentalService.create(dto));

        assertEquals("S500", ex.getCode());
        assertTrue(Thread.interrupted(), "中断标记应被恢复（此处读取后清除）");
    }

    // ------------------------------------------------------------------
    // 缴纳押金
    // ------------------------------------------------------------------

    @Test
    void pay_rejectsMissingOrder() {
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> rentalService.pay(ORDER_ID));

        assertEquals("C404", ex.getCode());
    }

    @Test
    void pay_rejectsOtherUsersOrder() {
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID + 1, 1, futureStart(), futureStart().plusHours(2)));

        BizException ex = assertThrows(BizException.class, () -> rentalService.pay(ORDER_ID));

        assertEquals("C403", ex.getCode());
    }

    @Test
    void pay_rejectsNonPendingOrder() {
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 2, futureStart(), futureStart().plusHours(2)));

        BizException ex = assertThrows(BizException.class, () -> rentalService.pay(ORDER_ID));

        assertEquals("C409", ex.getCode());
    }

    @Test
    void pay_marksDepositPaidAndPublishesExpireNotice() {
        LocalDateTime start = futureStart();
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 1, start, start.plusHours(2)));
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));

        rentalService.pay(ORDER_ID);

        ArgumentCaptor<RentalOrder> captor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getStatus());          // 已预约
        assertEquals(2, captor.getValue().getDepositStatus());   // 已缴
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.NOTIFY_DELAY_EXCHANGE),
                eq(RabbitMqConfig.NOTIFY_DELAY_ROUTING_KEY),
                any(NotifyMessage.class),
                any(MessagePostProcessor.class));
    }

    // ------------------------------------------------------------------
    // 取消预约
    // ------------------------------------------------------------------

    @Test
    void cancel_rejectsInvalidStatus() {
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 3, futureStart(), futureStart().plusHours(2)));

        BizException ex = assertThrows(BizException.class, () -> rentalService.cancel(ORDER_ID));

        assertEquals("C409", ex.getCode());
    }

    @Test
    void cancel_rejectsWithin24HoursBeforePickup() {
        LocalDateTime start = LocalDateTime.now().plusHours(20);
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 1, start, start.plusHours(2)));

        BizException ex = assertThrows(BizException.class, () -> rentalService.cancel(ORDER_ID));

        assertEquals("C400", ex.getCode());
        assertTrue(ex.getMessage().contains("24 小时"));
    }

    @Test
    void cancel_refundsDepositWhenPaid() {
        LocalDateTime start = futureStart();
        RentalOrder order = order(ORDER_ID, USER_ID, 2, start, start.plusHours(2));
        order.setDepositStatus(2);
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(order);

        rentalService.cancel(ORDER_ID);

        ArgumentCaptor<RentalOrder> captor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).updateById(captor.capture());
        RentalOrder update = captor.getValue();
        assertEquals(5, update.getStatus());                 // 已取消
        assertEquals(3, update.getDepositStatus());          // 已退还
        assertEquals(0, new BigDecimal("100.00").compareTo(update.getDepositRefundAmount()));
        assertNotNull(update.getCancelTime());
    }

    @Test
    void cancel_doesNotRefundUnpaidDeposit() {
        LocalDateTime start = futureStart();
        RentalOrder order = order(ORDER_ID, USER_ID, 1, start, start.plusHours(2));
        order.setDepositStatus(1);
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(order);

        rentalService.cancel(ORDER_ID);

        ArgumentCaptor<RentalOrder> captor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).updateById(captor.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().getDepositRefundAmount()));
    }

    // ------------------------------------------------------------------
    // 取件
    // ------------------------------------------------------------------

    @Test
    void pickup_rejectsNonReservedOrder() {
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 1, futureStart(), futureStart().plusHours(2)));

        BizException ex = assertThrows(BizException.class,
                () -> rentalService.pickup(ORDER_ID, new RentalPickupDTO()));

        assertEquals("C409", ex.getCode());
    }

    @Test
    void pickup_rejectsUnavailableTool() {
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 2, futureStart(), futureStart().plusHours(2)));
        when(toolService.detail(TOOL_ID)).thenReturn(tool(2));

        BizException ex = assertThrows(BizException.class,
                () -> rentalService.pickup(ORDER_ID, new RentalPickupDTO()));

        assertEquals("C409", ex.getCode());
    }

    @Test
    void pickup_marksPickedUpAndUpdatesPickupName() {
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 2, futureStart(), futureStart().plusHours(2)));
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));
        RentalPickupDTO dto = new RentalPickupDTO();
        dto.setPickupName("李四（代取）");

        rentalService.pickup(ORDER_ID, dto);

        ArgumentCaptor<RentalOrder> orderCaptor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).updateById(orderCaptor.capture());
        assertEquals(3, orderCaptor.getValue().getStatus());  // 已取件
        assertEquals("李四（代取）", orderCaptor.getValue().getPickupName());

        ArgumentCaptor<ToolStatusDTO> toolCaptor = ArgumentCaptor.forClass(ToolStatusDTO.class);
        verify(toolService).updateStatus(eq(TOOL_ID), toolCaptor.capture());
        assertEquals(2, toolCaptor.getValue().getStatus());   // 已借出
    }

    @Test
    void pickup_keepsOriginalNameWhenDtoBlank() {
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 2, futureStart(), futureStart().plusHours(2)));
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));

        rentalService.pickup(ORDER_ID, null);

        ArgumentCaptor<RentalOrder> captor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).updateById(captor.capture());
        assertEquals(3, captor.getValue().getStatus());
        assertEquals(null, captor.getValue().getPickupName());
    }

    // ------------------------------------------------------------------
    // 归还验收
    // ------------------------------------------------------------------

    @Test
    void returnTool_rejectsNonPickedUpOrder() {
        when(rentalOrderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, USER_ID, 2, futureStart(), futureStart().plusHours(2)));

        BizException ex = assertThrows(BizException.class,
                () -> rentalService.returnTool(ORDER_ID, returnDto(1, "0", null)));

        assertEquals("C409", ex.getCode());
    }

    @Test
    void returnTool_calculatesRefundAndRestoresAvailableTool() {
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(returnableOrder());
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));

        rentalService.returnTool(ORDER_ID, returnDto(1, "10", null));

        ArgumentCaptor<RentalOrder> captor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).updateById(captor.capture());
        RentalOrder update = captor.getValue();
        assertEquals(4, update.getStatus());                   // 已归还
        assertEquals(3, update.getDepositStatus());            // 已退还
        assertEquals(0, new BigDecimal("10.00").compareTo(update.getOverdueFee()));
        assertEquals(0, new BigDecimal("90.00").compareTo(update.getDepositRefundAmount()));
        assertEquals(USER_ID, update.getUpdateBy().longValue());
        assertNotNull(update.getReturnTime());

        verify(toolService).updateDamageLevel(TOOL_ID, 1);     // 完好
        ArgumentCaptor<ToolStatusDTO> toolCaptor = ArgumentCaptor.forClass(ToolStatusDTO.class);
        verify(toolService).updateStatus(eq(TOOL_ID), toolCaptor.capture());
        assertEquals(1, toolCaptor.getValue().getStatus());    // 重新上架
    }

    @Test
    void returnTool_severeDamageSendsToolToRepair() {
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(returnableOrder());

        rentalService.returnTool(ORDER_ID, returnDto(3, "0", "100"));

        verify(toolService).updateDamageLevel(TOOL_ID, 4);     // 严重损坏
        ArgumentCaptor<ToolStatusDTO> captor = ArgumentCaptor.forClass(ToolStatusDTO.class);
        verify(toolService).updateStatus(eq(TOOL_ID), captor.capture());
        assertEquals(3, captor.getValue().getStatus());        // 维修中
    }

    @Test
    void returnTool_capsOverdueFeeAtDeposit() {
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(returnableOrder());
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));

        rentalService.returnTool(ORDER_ID, returnDto(2, "150", null));

        ArgumentCaptor<RentalOrder> captor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).updateById(captor.capture());
        assertEquals(0, new BigDecimal("100.00").compareTo(captor.getValue().getOverdueFee()));
        assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().getDepositRefundAmount()));
    }

    @Test
    void returnTool_clampsExplicitRefundToDepositRange() {
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(returnableOrder());

        rentalService.returnTool(ORDER_ID, returnDto(1, "0", "150"));

        ArgumentCaptor<RentalOrder> captor = ArgumentCaptor.forClass(RentalOrder.class);
        verify(rentalOrderMapper).updateById(captor.capture());
        assertEquals(0, new BigDecimal("100.00").compareTo(captor.getValue().getDepositRefundAmount()));
    }

    @Test
    void returnTool_publishesOverdueNoticeOnlyWhenFeePositive() {
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(returnableOrder());
        when(toolService.detail(TOOL_ID)).thenReturn(tool(1));

        rentalService.returnTool(ORDER_ID, returnDto(1, "20", null));

        ArgumentCaptor<NotifyMessage> captor = ArgumentCaptor.forClass(NotifyMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.NOTIFY_DELAY_EXCHANGE),
                eq(RabbitMqConfig.NOTIFY_DELAY_ROUTING_KEY),
                captor.capture(),
                any(MessagePostProcessor.class));
        assertEquals(3, captor.getValue().getType());          // 逾期提醒
        assertEquals("SQ-ZY-20261020-0001", captor.getValue().getBizNo());
    }

    @Test
    void returnTool_skipsNoticeWhenNoOverdueFee() {
        when(rentalOrderMapper.selectById(ORDER_ID)).thenReturn(returnableOrder());

        rentalService.returnTool(ORDER_ID, returnDto(1, "0", null));

        verifyNoInteractions(rabbitTemplate);
    }

    // ------------------------------------------------------------------
    // 记录查询 / 导出
    // ------------------------------------------------------------------

    @Test
    void detail_rejectsMissingOrder() {
        when(rentalOrderMapper.selectVoById(ORDER_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> rentalService.detail(ORDER_ID));

        assertEquals("C404", ex.getCode());
    }

    @Test
    void detail_forbidsResidentFromViewingOthersOrder() {
        RentalOrderVO vo = orderVo();
        vo.setUserId(USER_ID + 1);
        when(rentalOrderMapper.selectVoById(ORDER_ID)).thenReturn(vo);

        BizException ex = assertThrows(BizException.class, () -> rentalService.detail(ORDER_ID));

        assertEquals("C403", ex.getCode());
    }

    @Test
    void detail_masksPhoneAndAllowsStaff() {
        RentalOrderVO vo = orderVo();
        vo.setUserId(USER_ID + 1);
        vo.setUserPhone("13812345678");
        when(rentalOrderMapper.selectVoById(ORDER_ID)).thenReturn(vo);
        stp.when(() -> StpUtil.hasRole(RoleConstant.STAFF)).thenReturn(true);

        RentalOrderVO result = rentalService.detail(ORDER_ID);

        assertEquals("138****5678", result.getUserPhone());
    }

    @Test
    void page_forResidentFiltersByCurrentUserAndMasksPhone() {
        RentalQueryDTO query = new RentalQueryDTO();
        RentalOrderVO vo = orderVo();
        vo.setUserPhone("13812345678");
        when(rentalOrderMapper.selectVoPage(any(), eq(query), eq(USER_ID))).thenAnswer(inv -> {
            Page<RentalOrderVO> page = inv.getArgument(0);
            page.setRecords(List.of(vo));
            page.setTotal(1);
            return page;
        });

        PageResult<RentalOrderVO> result = rentalService.page(query);

        assertEquals(1, result.getTotal());
        assertEquals("138****5678", result.getRecords().getFirst().getUserPhone());
        verify(rentalOrderMapper).selectVoPage(any(), eq(query), eq(USER_ID));
    }

    @Test
    void page_forStaffQueriesAllUsers() {
        RentalQueryDTO query = new RentalQueryDTO();
        when(rentalOrderMapper.selectVoPage(any(), eq(query), isNull())).thenAnswer(inv -> {
            Page<RentalOrderVO> page = inv.getArgument(0);
            page.setRecords(List.of());
            return page;
        });
        stp.when(() -> StpUtil.hasRole(RoleConstant.STAFF)).thenReturn(true);

        rentalService.page(query);

        verify(rentalOrderMapper).selectVoPage(any(), eq(query), isNull());
    }

    @Test
    void export_forbidsResident() {
        BizException ex = assertThrows(BizException.class,
                () -> rentalService.export(new RentalQueryDTO(), new MockHttpServletResponse()));

        assertEquals("C403", ex.getCode());
        verifyNoInteractions(rentalOrderMapper);
    }

    @Test
    void export_writesExcelForStaff() {
        RentalQueryDTO query = new RentalQueryDTO();
        RentalOrderVO vo = orderVo();
        vo.setUserPhone("13812345678");
        when(rentalOrderMapper.selectVoList(query, null)).thenReturn(List.of(vo));
        stp.when(() -> StpUtil.hasRole(RoleConstant.STAFF)).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        rentalService.export(query, response);

        assertTrue(response.getContentType().startsWith("application/vnd.ms-excel"));
        assertTrue(response.getHeader("Content-Disposition").contains("filename="));
        assertTrue(response.getContentAsByteArray().length > 0, "导出内容不应为空");
    }

    // ------------------------------------------------------------------
    // 测试数据
    // ------------------------------------------------------------------

    private RentalCreateDTO futureDto() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0)
                .withSecond(0).withNano(0);
        return dto(TOOL_ID, start, start.plusHours(2), "张三");
    }

    private static LocalDateTime futureStart() {
        return LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private static RentalCreateDTO dto(Long toolId, LocalDateTime start, LocalDateTime end, String pickupName) {
        RentalCreateDTO dto = new RentalCreateDTO();
        dto.setToolId(toolId);
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setPickupName(pickupName);
        return dto;
    }

    private static RentalReturnDTO returnDto(int acceptResult, String overdueFee, String refund) {
        RentalReturnDTO dto = new RentalReturnDTO();
        dto.setAcceptResult(acceptResult);
        dto.setOverdueFee(new BigDecimal(overdueFee));
        dto.setDepositRefundAmount(refund == null ? null : new BigDecimal(refund));
        return dto;
    }

    private static Tool tool(int status) {
        Tool tool = new Tool();
        tool.setId(RentalServiceImplTest.TOOL_ID);
        tool.setName("电钻");
        tool.setToolCode("SQ-G-WX-0001");
        tool.setStatus(status);
        tool.setDeposit(new BigDecimal("100.00"));
        return tool;
    }

    private static RentalOrder order(Long id, Long userId, int status,
                                     LocalDateTime start, LocalDateTime end) {
        RentalOrder order = new RentalOrder();
        order.setId(id);
        order.setOrderNo("SQ-ZY-20261020-0001");
        order.setToolId(RentalServiceImplTest.TOOL_ID);
        order.setUserId(userId);
        order.setPickupName("张三");
        order.setStartTime(start);
        order.setEndTime(end);
        order.setDepositAmount(new BigDecimal("100.00"));
        order.setDepositStatus(1);
        order.setStatus(status);
        return order;
    }

    private static RentalOrder returnableOrder() {
        LocalDateTime start = futureStart();
        RentalOrder order = order(ORDER_ID, USER_ID, 3, start, start.plusHours(2));
        order.setDepositStatus(2);
        return order;
    }

    private static RentalOrderVO orderVo() {
        RentalOrderVO vo = new RentalOrderVO();
        vo.setId(ORDER_ID);
        vo.setOrderNo("SQ-ZY-20261020-0001");
        vo.setToolId(TOOL_ID);
        vo.setToolCode("SQ-G-WX-0001");
        vo.setToolName("电钻");
        vo.setUserId(USER_ID);
        vo.setUserName("张三");
        vo.setUserPhone("13812345678");
        vo.setPickupName("张三");
        vo.setStartTime(futureStart());
        vo.setEndTime(futureStart().plusHours(2));
        vo.setDepositAmount(new BigDecimal("100.00"));
        vo.setDepositStatus(3);
        vo.setStatus(4);
        vo.setAcceptResult(1);
        vo.setOverdueFee(BigDecimal.ZERO);
        vo.setDepositRefundAmount(new BigDecimal("100.00"));
        vo.setCreateTime(LocalDateTime.now());
        return vo;
    }
}
