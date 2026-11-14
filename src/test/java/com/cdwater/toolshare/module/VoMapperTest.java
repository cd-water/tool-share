package com.cdwater.toolshare.module;

import com.cdwater.toolshare.module.auth.Admin;
import com.cdwater.toolshare.module.auth.AdminVoMapper;
import com.cdwater.toolshare.module.auth.AdminVO;
import com.cdwater.toolshare.module.auth.User;
import com.cdwater.toolshare.module.auth.UserVoMapper;
import com.cdwater.toolshare.module.auth.UserVO;
import com.cdwater.toolshare.module.damage.DamageReport;
import com.cdwater.toolshare.module.damage.DamageReportVoMapper;
import com.cdwater.toolshare.module.damage.DamageReportVO;
import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocVoMapper;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocVO;
import com.cdwater.toolshare.module.message.Message;
import com.cdwater.toolshare.module.message.MessageVoMapper;
import com.cdwater.toolshare.module.message.MessageVO;
import com.cdwater.toolshare.module.rental.Rental;
import com.cdwater.toolshare.module.rental.RentalVoMapper;
import com.cdwater.toolshare.module.rental.RentalVO;
import com.cdwater.toolshare.module.tool.Tool;
import com.cdwater.toolshare.module.tool.ToolLog;
import com.cdwater.toolshare.module.tool.ToolLogVoMapper;
import com.cdwater.toolshare.module.tool.ToolLogVO;
import com.cdwater.toolshare.module.tool.ToolVoMapper;
import com.cdwater.toolshare.module.tool.ToolVO;
import com.cdwater.toolshare.module.tool.ToolWatchVoMapper;
import com.cdwater.toolshare.module.tool.ToolWatchVO;
import com.cdwater.toolshare.shared.security.IdCardCipher;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoMapperTest {

    private final ToolVoMapper toolVoMapper = Mappers.getMapper(ToolVoMapper.class);
    private final ToolLogVoMapper toolLogVoMapper = Mappers.getMapper(ToolLogVoMapper.class);
    private final ToolWatchVoMapper toolWatchVoMapper = Mappers.getMapper(ToolWatchVoMapper.class);
    private final RentalVoMapper rentalVoMapper = Mappers.getMapper(RentalVoMapper.class);
    private final DamageReportVoMapper damageReportVoMapper = Mappers.getMapper(DamageReportVoMapper.class);
    private final MessageVoMapper messageVoMapper = Mappers.getMapper(MessageVoMapper.class);
    private final UserVoMapper userVoMapper = Mappers.getMapper(UserVoMapper.class);
    private final AdminVoMapper adminVoMapper = Mappers.getMapper(AdminVoMapper.class);
    private final KnowledgeDocVoMapper knowledgeDocVoMapper = Mappers.getMapper(KnowledgeDocVoMapper.class);

    @Test
    void tool_maps_fields_and_derived_names() {
        Tool tool = new Tool();
        tool.setId(1L);
        tool.setCode("SQ-G-WX-0001");
        tool.setName("手电钻");
        tool.setCategory("WX");
        tool.setDamageLevel("LIGHT");
        tool.setStatus("AVAILABLE");
        tool.setDeposit(new BigDecimal("200.00"));
        tool.setImageUrls("a.jpg, b.jpg");

        ToolVO vo = toolVoMapper.toVO(tool);

        assertEquals(1L, vo.getId());
        assertEquals("手电钻", vo.getName());
        assertEquals("维修工具", vo.getCategoryName());
        assertEquals("轻微磨损", vo.getDamageLevelName());
        assertEquals("可借用", vo.getStatusName());
        assertEquals(List.of("a.jpg", "b.jpg"), vo.getImageUrls());
    }

    @Test
    void tool_image_list_is_empty_when_blank() {
        Tool tool = new Tool();
        tool.setImageUrls("  ,  ");
        assertEquals(List.of(), toolVoMapper.toVO(tool).getImageUrls());
        assertEquals(List.of(), toolVoMapper.toVO(new Tool()).getImageUrls());
    }

    @Test
    void tool_watch_maps_tool_id_and_defaults_deposit() {
        Tool tool = new Tool();
        tool.setId(7L);
        tool.setStatus("MAINTENANCE");

        ToolWatchVO vo = toolWatchVoMapper.toVO(tool);

        assertEquals(7L, vo.getToolId());
        assertEquals("维修中", vo.getStatusName());
        assertEquals(BigDecimal.ZERO, vo.getDeposit());
    }

    @Test
    void tool_log_maps_all_fields() {
        ToolLog log = new ToolLog();
        log.setId(3L);
        log.setToolId(1L);
        log.setAction("CREATE");
        log.setDetail("新增");
        log.setOperatorId(9L);
        LocalDateTime now = LocalDateTime.now();
        log.setCreateTime(now);

        ToolLogVO vo = toolLogVoMapper.toVO(log);

        assertEquals(3L, vo.getId());
        assertEquals(1L, vo.getToolId());
        assertEquals("CREATE", vo.getAction());
        assertEquals("新增", vo.getDetail());
        assertEquals(9L, vo.getOperatorId());
        assertEquals(now, vo.getCreateTime());
    }

    @Test
    void rental_masks_phone_and_derives_names() {
        Rental rental = new Rental();
        rental.setId(2L);
        rental.setOrderNo("SQ-ZY-0001");
        rental.setRenterPhone("13800000001");
        rental.setDepositStatus("PAID");
        rental.setStatus("RESERVED");
        rental.setDamageResult("INTACT");

        RentalVO vo = rentalVoMapper.toVO(rental);

        assertEquals("138****0001", vo.getRenterPhone());
        assertEquals("已支付", vo.getDepositStatusName());
        assertEquals("已预约", vo.getStatusName());
        assertEquals("完好", vo.getDamageResultName());
        // 联表/外部查询字段由 service 填充
        assertNull(vo.getToolImageUrl());
        assertNull(vo.getRemindAt());
        assertNull(vo.getRemindBeforeMinutes());
    }

    @Test
    void damage_report_masks_phone_parses_images_and_derives_names() {
        DamageReport report = new DamageReport();
        report.setId(4L);
        report.setReporterPhone("13900001111");
        report.setImages("x.jpg,y.jpg");
        report.setStatus("REPAIRING");
        report.setProgressStatus("WAITING_PARTS");
        report.setAcceptResult("PARTIAL");

        DamageReportVO vo = damageReportVoMapper.toVO(report);

        assertEquals("139****1111", vo.getReporterPhone());
        assertEquals(List.of("x.jpg", "y.jpg"), vo.getImages());
        assertEquals("维修中", vo.getStatusName());
        assertEquals("等待配件", vo.getProgressStatusName());
        assertEquals("部分合格", vo.getAcceptResultName());
        assertNull(vo.getToolImageUrl());
    }

    @Test
    void message_maps_read_status_and_type_name() {
        Message message = new Message();
        message.setId(5L);
        message.setType("OVERDUE");
        message.setReadStatus(1);

        MessageVO read = messageVoMapper.toVO(message);
        assertEquals("逾期提醒", read.getTypeName());
        assertEquals(true, read.getReadStatus());

        message.setReadStatus(0);
        assertEquals(false, messageVoMapper.toVO(message).getReadStatus());
        message.setReadStatus(null);
        assertFalse(messageVoMapper.toVO(message).getReadStatus());
    }

    @Test
    void user_masks_phone_and_encrypted_id_card() {
        IdCardCipher cipher = new IdCardCipher("0123456789abcdef");
        User user = new User();
        user.setId(1L);
        user.setPhone("13800000001");
        user.setNickname("user_mjafo8");
        user.setRealName("王建国");
        user.setIdCard(cipher.encrypt("310101199001011234"));

        UserVO vo = userVoMapper.toVO(user, cipher);

        assertEquals(1L, vo.getId());
        assertEquals("138****0001", vo.getPhone());
        assertEquals("user_mjafo8", vo.getNickname());
        assertEquals("王建国", vo.getRealName());
        assertEquals("3101********1234", vo.getIdCard());
    }

    @Test
    void admin_maps_id_and_username() {
        Admin admin = new Admin();
        admin.setId(1L);
        admin.setUsername("admin");
        AdminVO vo = adminVoMapper.toVO(admin);
        assertEquals(1L, vo.getId());
        assertEquals("admin", vo.getUsername());
    }

    @Test
    void knowledge_preview_is_collapsed_and_truncated() {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(6L);
        doc.setTitle("规则");
        doc.setFileName("rules.txt");
        doc.setContent("  社区共享\n工具租赁规则  ");
        assertEquals("社区共享 工具租赁规则", knowledgeDocVoMapper.toVO(doc).getContentPreview());

        doc.setContent("a".repeat(130));
        KnowledgeDocVO vo = knowledgeDocVoMapper.toVO(doc);
        assertEquals(121, vo.getContentPreview().length());
        assertTrue(vo.getContentPreview().endsWith("…"));
    }

    @Test
    void all_mappers_are_null_safe() {
        assertNull(toolVoMapper.toVO(null));
        assertNull(toolLogVoMapper.toVO(null));
        assertNull(toolWatchVoMapper.toVO(null));
        assertNull(rentalVoMapper.toVO(null));
        assertNull(damageReportVoMapper.toVO(null));
        assertNull(messageVoMapper.toVO(null));
        assertNull(userVoMapper.toVO(null, new IdCardCipher("0123456789abcdef")));
        assertNull(adminVoMapper.toVO(null));
        assertNull(knowledgeDocVoMapper.toVO(null));
    }
}
