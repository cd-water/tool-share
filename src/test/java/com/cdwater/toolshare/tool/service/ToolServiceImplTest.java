package com.cdwater.toolshare.tool.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.exception.BizException;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.tool.dto.ToolAuditDTO;
import com.cdwater.toolshare.tool.dto.ToolDTO;
import com.cdwater.toolshare.tool.dto.ToolQueryDTO;
import com.cdwater.toolshare.tool.dto.ToolStatusDTO;
import com.cdwater.toolshare.tool.entity.Tool;
import com.cdwater.toolshare.tool.entity.ToolStatusLog;
import com.cdwater.toolshare.tool.mapper.ToolMapper;
import com.cdwater.toolshare.tool.mapper.ToolStatusLogMapper;
import com.cdwater.toolshare.tool.service.impl.ToolServiceImpl;
import com.cdwater.toolshare.tool.util.ToolCodeGenerator;
import com.cdwater.toolshare.tool.vo.ToolCreateVO;
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
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工具业务单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolServiceImplTest {

    private static final long TOOL_ID = 7L;
    private static final long OPERATOR_ID = 99L;

    @Mock
    private ToolMapper toolMapper;

    @Mock
    private ToolStatusLogMapper toolStatusLogMapper;

    @Mock
    private ToolCodeGenerator toolCodeGenerator;

    private ToolServiceImpl toolService;

    private MockedStatic<StpUtil> stp;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Tool.class);
        TableInfoHelper.initTableInfo(assistant, ToolStatusLog.class);
    }

    @BeforeEach
    void setUp() {
        toolService = new ToolServiceImpl(toolMapper, toolStatusLogMapper, toolCodeGenerator);
        stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(OPERATOR_ID);
    }

    @AfterEach
    void tearDown() {
        stp.close();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    void detail_throwsWhenToolMissing() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> toolService.detail(TOOL_ID));

        assertEquals("C404", ex.getCode());
    }

    @Test
    void detail_returnsTool() {
        Tool tool = tool(1);
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool);

        assertSame(tool, toolService.detail(TOOL_ID));
    }

    @Test
    void page_mapsRecordsAndPagination() {
        ToolQueryDTO query = new ToolQueryDTO();
        query.setKeyword("电钻");
        query.setCategory(1);
        query.setStatus(1);
        query.setStorageLocation("A 区");
        when(toolMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Tool> page = inv.getArgument(0);
            page.setRecords(List.of(tool(1)));
            page.setTotal(1);
            return page;
        });

        PageResult<Tool> result = toolService.page(query);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
    }

    @Test
    void statusLogs_returnsMapperResult() {
        List<ToolStatusLog> logs = List.of(new ToolStatusLog());
        when(toolStatusLogMapper.selectList(any())).thenReturn(logs);

        assertSame(logs, toolService.statusLogs(TOOL_ID));
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    @Test
    void create_persistsToolWithGeneratedCodeAndAuditState() {
        when(toolCodeGenerator.next(1)).thenReturn("SQ-G-WX-0001");
        doAnswer(inv -> {
            inv.<Tool>getArgument(0).setId(TOOL_ID);
            return 1;
        }).when(toolMapper).insert(any(Tool.class));

        ToolCreateVO vo = toolService.create(toolDto());

        assertEquals(TOOL_ID, vo.getId());
        assertEquals("SQ-G-WX-0001", vo.getToolCode());

        ArgumentCaptor<Tool> captor = ArgumentCaptor.forClass(Tool.class);
        verify(toolMapper).insert(captor.capture());
        Tool saved = captor.getValue();
        assertEquals("SQ-G-WX-0001", saved.getToolCode());
        assertEquals("电钻", saved.getName());
        assertEquals(1, saved.getStatus());        // 可用
        assertEquals(1, saved.getAuditStatus());   // 待审核
        assertEquals(OPERATOR_ID, saved.getCreateBy().longValue());
    }

    @Test
    void create_retriesOnDuplicateCodeAndSyncsCounter() {
        when(toolCodeGenerator.next(1)).thenReturn("SQ-G-WX-0001", "SQ-G-WX-0002");
        doThrow(new DuplicateKeyException("duplicate"))
                .doAnswer(inv -> {
                    inv.<Tool>getArgument(0).setId(TOOL_ID);
                    return 1;
                })
                .when(toolMapper).insert(any(Tool.class));

        ToolCreateVO vo = toolService.create(toolDto());

        assertEquals("SQ-G-WX-0002", vo.getToolCode());
        verify(toolCodeGenerator, times(2)).next(1);
        verify(toolCodeGenerator, times(1)).syncFromDatabase(1);
    }

    @Test
    void create_givesUpAfterMaxRetries() {
        when(toolCodeGenerator.next(1)).thenReturn("SQ-G-WX-0001");
        doThrow(new DuplicateKeyException("duplicate")).when(toolMapper).insert(any(Tool.class));

        BizException ex = assertThrows(BizException.class, () -> toolService.create(toolDto()));

        assertEquals("C409", ex.getCode());
        verify(toolCodeGenerator, times(3)).next(1);
        verify(toolCodeGenerator, times(3)).syncFromDatabase(1);
    }

    // ------------------------------------------------------------------
    // 更新
    // ------------------------------------------------------------------

    @Test
    void update_throwsWhenToolMissing() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> toolService.update(TOOL_ID, toolDto()));

        assertEquals("C404", ex.getCode());
        verify(toolMapper, never()).updateById(any(Tool.class));
    }

    @Test
    void update_copiesEditableFieldsAndKeepsCodeUntouched() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool(1));

        toolService.update(TOOL_ID, toolDto());

        ArgumentCaptor<Tool> captor = ArgumentCaptor.forClass(Tool.class);
        verify(toolMapper).updateById(captor.capture());
        Tool update = captor.getValue();
        assertEquals(TOOL_ID, update.getId());
        assertEquals("电钻", update.getName());
        assertEquals(new BigDecimal("50.00"), update.getDeposit());
        assertNull(update.getToolCode(), "工具编号不可修改");
        assertNull(update.getStatus(), "更新基础信息不应改状态");
    }

    @Test
    void updateStatus_writesStatusAndLog() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool(1));
        ToolStatusDTO dto = new ToolStatusDTO();
        dto.setStatus(3);
        dto.setRemark("损坏待修");

        toolService.updateStatus(TOOL_ID, dto);

        ArgumentCaptor<Tool> toolCaptor = ArgumentCaptor.forClass(Tool.class);
        verify(toolMapper).updateById(toolCaptor.capture());
        assertEquals(3, toolCaptor.getValue().getStatus());

        ArgumentCaptor<ToolStatusLog> logCaptor = ArgumentCaptor.forClass(ToolStatusLog.class);
        verify(toolStatusLogMapper).insert(logCaptor.capture());
        ToolStatusLog log = logCaptor.getValue();
        assertEquals(TOOL_ID, log.getToolId());
        assertEquals(1, log.getOldStatus());
        assertEquals(3, log.getNewStatus());
        assertEquals(OPERATOR_ID, log.getOperatorId().longValue());
        assertEquals("损坏待修", log.getRemark());
    }

    @Test
    void updateDamageLevel_validatesRange() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool(1));

        assertEquals("C400", assertThrows(BizException.class,
                () -> toolService.updateDamageLevel(TOOL_ID, 0)).getCode());
        assertEquals("C400", assertThrows(BizException.class,
                () -> toolService.updateDamageLevel(TOOL_ID, 5)).getCode());
        assertEquals("C400", assertThrows(BizException.class,
                () -> toolService.updateDamageLevel(TOOL_ID, null)).getCode());
        verify(toolMapper, never()).updateById(any(Tool.class));
    }

    @Test
    void updateDamageLevel_updatesWhenValid() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool(1));

        toolService.updateDamageLevel(TOOL_ID, 4);

        ArgumentCaptor<Tool> captor = ArgumentCaptor.forClass(Tool.class);
        verify(toolMapper).updateById(captor.capture());
        assertEquals(4, captor.getValue().getDamageLevel());
    }

    // ------------------------------------------------------------------
    // 审核
    // ------------------------------------------------------------------

    @Test
    void audit_throwsWhenToolMissing() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> toolService.audit(TOOL_ID, auditDto(2, null)));

        assertEquals("C404", ex.getCode());
        verifyNoInteractions(toolStatusLogMapper);
    }

    @Test
    void audit_revisionRequiresOpinion() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool(1));

        BizException ex = assertThrows(BizException.class,
                () -> toolService.audit(TOOL_ID, auditDto(3, "  ")));

        assertEquals("C400", ex.getCode());
        verify(toolMapper, never()).update(any(Tool.class), any());
    }

    @Test
    void audit_revisionStoresOpinion() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool(1));

        toolService.audit(TOOL_ID, auditDto(3, "请补充工具图片"));

        ArgumentCaptor<Tool> captor = ArgumentCaptor.forClass(Tool.class);
        verify(toolMapper).update(captor.capture(), any());
        assertEquals(3, captor.getValue().getAuditStatus());
        assertEquals("请补充工具图片", captor.getValue().getAuditOpinion());
    }

    @Test
    void audit_approvalClearsOpinion() {
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool(1));

        toolService.audit(TOOL_ID, auditDto(2, "历史意见"));

        ArgumentCaptor<Tool> captor = ArgumentCaptor.forClass(Tool.class);
        verify(toolMapper).update(captor.capture(), any());
        assertEquals(2, captor.getValue().getAuditStatus());
        assertNull(captor.getValue().getAuditOpinion());
    }

    // ------------------------------------------------------------------
    // 测试数据
    // ------------------------------------------------------------------

    private static Tool tool(int status) {
        Tool tool = new Tool();
        tool.setId(TOOL_ID);
        tool.setToolCode("SQ-G-WX-0001");
        tool.setName("电钻");
        tool.setStatus(status);
        tool.setDeposit(new BigDecimal("50.00"));
        return tool;
    }

    private static ToolDTO toolDto() {
        ToolDTO dto = new ToolDTO();
        dto.setName("电钻");
        dto.setModelSpec("12V 锂电");
        dto.setCategory(1);
        dto.setDamageLevel(1);
        dto.setDeposit(new BigDecimal("50.00"));
        dto.setStorageLocation("A 区 1 号柜");
        dto.setUsageInstruction("使用前请阅读说明");
        dto.setImages(List.of("http://localhost:9000/tool-share/a.jpg"));
        dto.setSupplierInfo("社区五金店");
        return dto;
    }

    private static ToolAuditDTO auditDto(int status, String opinion) {
        ToolAuditDTO dto = new ToolAuditDTO();
        dto.setAuditStatus(status);
        dto.setAuditOpinion(opinion);
        return dto;
    }
}
