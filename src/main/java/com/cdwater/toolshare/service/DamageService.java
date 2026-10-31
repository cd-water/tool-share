package com.cdwater.toolshare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdwater.toolshare.common.BizException;
import com.cdwater.toolshare.common.Dict;
import com.cdwater.toolshare.common.ResultCode;
import com.cdwater.toolshare.config.StpKit;
import com.cdwater.toolshare.dto.DamageAcceptDTO;
import com.cdwater.toolshare.dto.DamageCreateDTO;
import com.cdwater.toolshare.dto.DamageProgressDTO;
import com.cdwater.toolshare.dto.DamageVerifyDTO;
import com.cdwater.toolshare.entity.Admin;
import com.cdwater.toolshare.entity.DamageReport;
import com.cdwater.toolshare.entity.Rental;
import com.cdwater.toolshare.entity.Tool;
import com.cdwater.toolshare.entity.User;
import com.cdwater.toolshare.mapper.AdminMapper;
import com.cdwater.toolshare.mapper.DamageReportMapper;
import com.cdwater.toolshare.mapper.RentalMapper;
import com.cdwater.toolshare.mapper.ToolMapper;
import com.cdwater.toolshare.mapper.UserMapper;
import com.cdwater.toolshare.vo.DamageReportVO;
import com.cdwater.toolshare.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DamageService {

    private final DamageReportMapper damageReportMapper;
    private final RentalMapper rentalMapper;
    private final ToolMapper toolMapper;
    private final UserMapper userMapper;
    private final AdminMapper adminMapper;
    private final ToolService toolService;
    private final SequenceService sequenceService;
    private final MessageService messageService;

    @Transactional
    public DamageReportVO create(DamageCreateDTO dto) {
        Long userId = StpKit.USER.getLoginIdAsLong();
        Rental rental = rentalMapper.selectById(dto.getRentalId());
        if (rental == null || !rental.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "租赁记录不存在");
        }
        if (!"PICKED_UP".equals(rental.getStatus()) && !"OVERDUE".equals(rental.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "仅使用中的租赁可以报修");
        }
        long active = damageReportMapper.selectCount(new LambdaQueryWrapper<DamageReport>()
                .eq(DamageReport::getRentalId, rental.getId())
                .notIn(DamageReport::getStatus, "COMPLETED", "REJECTED", "SCRAPPED"));
        if (active > 0) {
            throw new BizException(ResultCode.BAD_REQUEST, "该租赁已有处理中的报修单");
        }
        User user = userMapper.selectById(userId);
        DamageReport report = new DamageReport();
        report.setReportNo(sequenceService.nextDamageReportNo());
        report.setRentalId(rental.getId());
        report.setToolId(rental.getToolId());
        report.setToolCode(rental.getToolCode());
        report.setToolName(rental.getToolName());
        report.setUserId(userId);
        report.setReporterName(displayName(user));
        report.setReporterPhone(user == null ? rental.getRenterPhone() : user.getPhone());
        report.setDamagePart(dto.getDamagePart().trim());
        report.setDescription(dto.getDescription().trim());
        report.setImages(dto.getImages() == null ? "" : String.join(",", dto.getImages()));
        report.setStatus("SUBMITTED");
        damageReportMapper.insert(report);
        messageService.notifyAdmins("REPAIR", "新的损坏报修",
                report.getReporterName() + " 提交了报修单 " + report.getReportNo()
                        + "（工具 " + report.getToolCode() + "），请及时核验。",
                "REPAIR_SUBMIT:" + report.getId());
        return toVO(report);
    }

    @Transactional
    public void verify(Long id, DamageVerifyDTO dto) {
        DamageReport report = getReport(id);
        if (!"SUBMITTED".equals(report.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "仅待核验的报修可核验");
        }
        if (!List.of("CONFIRMED", "SUSPECTED", "FALSE").contains(dto.getVerifyResult())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的核验结果");
        }
        report.setVerifier(currentAdminName());
        report.setVerifyTime(LocalDateTime.now());
        report.setVerifyResult(dto.getVerifyResult());
        report.setRepairPerson(dto.getRepairPerson());
        report.setRepairEstimate(dto.getRepairEstimate());
        report.setRemark(dto.getRemark());
        if ("FALSE".equals(dto.getVerifyResult())) {
            report.setStatus("REJECTED");
            damageReportMapper.updateById(report);
            messageService.notifyUser(report.getUserId(), "REPAIR", "报修已驳回",
                    "报修单 " + report.getReportNo() + " 经核验为误报，工具无需维修。");
            return;
        }
        report.setStatus("VERIFIED");
        damageReportMapper.updateById(report);
        Tool tool = toolService.getTool(report.getToolId());
        tool.setStatus("MAINTENANCE");
        toolMapper.updateById(tool);
        toolService.log(tool.getId(), "STATUS_CHANGE", "报修核验确认，工具转维修中");
        messageService.notifyUser(report.getUserId(), "REPAIR", "报修已确认",
                "报修单 " + report.getReportNo() + " 已核验，维修人员将尽快处理。");
    }

    @Transactional
    public void progress(Long id, DamageProgressDTO dto) {
        DamageReport report = getReport(id);
        if (!"VERIFIED".equals(report.getStatus()) && !"REPAIRING".equals(report.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "当前报修状态不可更新维修进度");
        }
        if (!Dict.containsKey(Dict.PROGRESS_STATUS, dto.getProgressStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的维修进度");
        }
        report.setStatus("REPAIRING");
        report.setProgressStatus(dto.getProgressStatus());
        report.setProgressNote(dto.getProgressNote());
        report.setProgressUpdateTime(LocalDateTime.now());
        if (dto.getRepairCost() != null) {
            report.setRepairCost(dto.getRepairCost());
        }
        if (StringUtils.hasText(dto.getRepairEstimate())) {
            report.setRepairEstimate(dto.getRepairEstimate());
        }
        if (StringUtils.hasText(dto.getRepairPerson())) {
            report.setRepairPerson(dto.getRepairPerson());
        }
        if (report.getRepairStartTime() == null) {
            report.setRepairStartTime(LocalDateTime.now());
        }
        damageReportMapper.updateById(report);
        messageService.notifyUser(report.getUserId(), "REPAIR", "维修进度更新",
                "报修单 " + report.getReportNo() + " 进度："
                        + Dict.label(Dict.PROGRESS_STATUS, dto.getProgressStatus())
                        + "。" + dto.getProgressNote());
    }

    @Transactional
    public void accept(Long id, DamageAcceptDTO dto) {
        DamageReport report = getReport(id);
        if (!"REPAIRING".equals(report.getStatus()) && !"VERIFIED".equals(report.getStatus())) {
            throw new BizException(ResultCode.BAD_REQUEST, "当前报修状态不可验收");
        }
        if (!Dict.containsKey(Dict.ACCEPT_RESULT, dto.getAcceptResult())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的验收结果");
        }
        if (!Dict.containsKey(Dict.TOOL_STATUS, dto.getToolStatusAfter())) {
            throw new BizException(ResultCode.BAD_REQUEST, "无效的工具状态");
        }
        String toolStatus = dto.getToolStatusAfter();
        if ("UNQUALIFIED".equals(dto.getAcceptResult())) {
            toolStatus = "MAINTENANCE";
        }
        report.setAcceptor(currentAdminName());
        report.setAcceptTime(LocalDateTime.now());
        report.setAcceptResult(dto.getAcceptResult());
        report.setToolStatusAfter(toolStatus);
        if (dto.getRepairCost() != null) {
            report.setRepairCost(dto.getRepairCost());
        }
        if (StringUtils.hasText(dto.getRemark())) {
            report.setRemark(dto.getRemark());
        }
        report.setStatus(switch (toolStatus) {
            case "AVAILABLE" -> "COMPLETED";
            case "SCRAPPED" -> "SCRAPPED";
            default -> "REPAIRING";
        });
        damageReportMapper.updateById(report);

        Tool tool = toolService.getTool(report.getToolId());
        tool.setStatus(toolStatus);
        tool.setLastRepairTime(LocalDateTime.now());
        if ("QUALIFIED".equals(dto.getAcceptResult())) {
            tool.setDamageLevel("LIGHT");
        } else if ("PARTIAL".equals(dto.getAcceptResult())) {
            tool.setDamageLevel("MEDIUM");
        } else {
            tool.setDamageLevel("SEVERE");
        }
        toolMapper.updateById(tool);
        toolService.log(tool.getId(), "STATUS_CHANGE", "维修验收：" + Dict.label(Dict.TOOL_STATUS, toolStatus));
        messageService.notifyUser(report.getUserId(), "REPAIR", "维修验收完成",
                "报修单 " + report.getReportNo() + " 验收结果："
                        + Dict.label(Dict.ACCEPT_RESULT, dto.getAcceptResult())
                        + "，工具状态：" + Dict.label(Dict.TOOL_STATUS, toolStatus) + "。");
    }

    public PageVO<DamageReportVO> userPage(long page, long size) {
        Page<DamageReport> result = damageReportMapper.selectPage(new Page<>(page, clamp(size)),
                new LambdaQueryWrapper<DamageReport>()
                        .eq(DamageReport::getUserId, StpKit.USER.getLoginIdAsLong())
                        .orderByDesc(DamageReport::getCreateTime));
        return PageVO.of(result, this::toVO);
    }

    public PageVO<DamageReportVO> adminPage(String keyword, String status, long page, long size) {
        LambdaQueryWrapper<DamageReport> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(DamageReport::getReportNo, keyword)
                    .or().like(DamageReport::getToolCode, keyword)
                    .or().like(DamageReport::getToolName, keyword)
                    .or().like(DamageReport::getReporterName, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(DamageReport::getStatus, status);
        }
        Page<DamageReport> result = damageReportMapper.selectPage(new Page<>(page, clamp(size)),
                wrapper.orderByDesc(DamageReport::getCreateTime));
        return PageVO.of(result, this::toVO);
    }

    public DamageReportVO userDetail(Long id) {
        DamageReport report = getReport(id);
        if (!report.getUserId().equals(StpKit.USER.getLoginIdAsLong())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看该报修单");
        }
        return toVO(report);
    }

    public DamageReportVO adminDetail(Long id) {
        return toVO(getReport(id));
    }

    private DamageReport getReport(Long id) {
        DamageReport report = damageReportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ResultCode.NOT_FOUND, "报修单不存在");
        }
        return report;
    }

    private String displayName(User user) {
        if (user == null) {
            return "居民";
        }
        return StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getNickname();
    }

    private String currentAdminName() {
        Admin admin = adminMapper.selectById(StpKit.ADMIN.getLoginIdAsLong());
        return admin == null ? "管理员" : admin.getUsername();
    }

    private DamageReportVO toVO(DamageReport report) {
        DamageReportVO vo = new DamageReportVO();
        vo.setId(report.getId());
        vo.setReportNo(report.getReportNo());
        vo.setRentalId(report.getRentalId());
        vo.setToolId(report.getToolId());
        vo.setToolCode(report.getToolCode());
        vo.setToolName(report.getToolName());
        vo.setUserId(report.getUserId());
        vo.setReporterName(report.getReporterName());
        vo.setReporterPhone(IdCardCipher.maskPhone(report.getReporterPhone()));
        vo.setDamagePart(report.getDamagePart());
        vo.setDescription(report.getDescription());
        vo.setImages(parseImages(report.getImages()));
        vo.setStatus(report.getStatus());
        vo.setStatusName(Dict.label(Dict.REPORT_STATUS, report.getStatus()));
        vo.setVerifier(report.getVerifier());
        vo.setVerifyTime(report.getVerifyTime());
        vo.setVerifyResult(report.getVerifyResult());
        vo.setRepairPerson(report.getRepairPerson());
        vo.setRepairEstimate(report.getRepairEstimate());
        vo.setRepairStartTime(report.getRepairStartTime());
        vo.setRepairCost(report.getRepairCost());
        vo.setProgressStatus(report.getProgressStatus());
        vo.setProgressStatusName(Dict.label(Dict.PROGRESS_STATUS, report.getProgressStatus()));
        vo.setProgressUpdateTime(report.getProgressUpdateTime());
        vo.setProgressNote(report.getProgressNote());
        vo.setAcceptor(report.getAcceptor());
        vo.setAcceptTime(report.getAcceptTime());
        vo.setAcceptResult(report.getAcceptResult());
        vo.setAcceptResultName(Dict.label(Dict.ACCEPT_RESULT, report.getAcceptResult()));
        vo.setToolStatusAfter(report.getToolStatusAfter());
        vo.setRemark(report.getRemark());
        vo.setCreateTime(report.getCreateTime());
        vo.setUpdateTime(report.getUpdateTime());
        return vo;
    }

    private List<String> parseImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private long clamp(long size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
