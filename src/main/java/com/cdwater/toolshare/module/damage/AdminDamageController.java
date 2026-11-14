package com.cdwater.toolshare.module.damage;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.damage.DamageAcceptDTO;
import com.cdwater.toolshare.module.damage.DamageProgressDTO;
import com.cdwater.toolshare.module.damage.DamageVerifyDTO;
import com.cdwater.toolshare.module.damage.DamageService;
import com.cdwater.toolshare.module.damage.DamageReportVO;
import com.cdwater.toolshare.shared.web.PageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端损坏报修控制器。
 * <p>提供报修单的分页查询、详情查看、核验、维修进度更新与验收五类接口，
 * 统一挂载在 {@code /api/admin/damage-reports} 下，并要求管理员具备
 * {@code damage:manage} 权限。
 * <p>本类只负责请求参数接收、校验触发与统一结果包装；报修状态迁移、
 * 并发控制、工具状态联动以及消息通知等业务规则全部委托给
 * <p>接口按流程顺序划分为：查询 → 核验 → 维修进度 → 验收。每个写接口
 * 都要求管理员权限，并由服务层再次校验报修单当前状态，防止越序操作。
 * {@link DamageService}，避免 Web 层与领域逻辑耦合。
 */
@RestController
@RequestMapping("/api/admin/damage-reports")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "damage:manage")
public class AdminDamageController {

    /** 报修领域服务：承载状态机、幂等校验与工具/消息联动。 */
    private final DamageService damageService;

    /**
     * 分页查询报修单（管理端）。
     * <p>关键字同时匹配报修单号、工具编号、工具名称与报修人姓名；
     * 状态为空时返回全部状态，结果按创建时间倒序排列。
     *
     * @param keyword 可选模糊关键字
     * @param status  可选报修状态编码
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return 报修单分页结果，列表项已补齐工具主图与字典名称
     * <p>管理端不限制报修人，因此可用于跨居民的维修调度与统计。
     */
    @GetMapping
    public Result<PageVO<DamageReportVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(damageService.adminPage(keyword, status, page, size));
    }

    /**
     * 查看报修单详情（管理端）。
     * <p>管理员不受报修人归属限制，可查看任意报修单。
     *
     * @param id 报修单主键
     * @return 报修单详情，包含状态、进度与验收等完整字段
     * <p>详情用于核验与验收前的信息确认，包含脱敏后的联系电话。
     */
    @GetMapping("/{id}")
    public Result<DamageReportVO> detail(@PathVariable Long id) {
        return Result.success(damageService.adminDetail(id));
    }

    /**
     * 核验报修单。
     * <p>仅待核验（SUBMITTED）的报修单可核验；核验为误报时直接驳回，
     * 确认或存疑时把关联工具切换为维修中并通知报修人。
     *
     * @param id  报修单主键
     * @param dto 核验结果、维修负责人、预计时长与备注
     * <p>核验成功后报修单状态不再允许回退，误操作只能通过驳回或后续流程处理。
     * @return 空结果
     */
    @PostMapping("/{id}/verify")
    public Result<Void> verify(@PathVariable Long id, @Valid @RequestBody DamageVerifyDTO dto) {
        damageService.verify(id, dto);
        return Result.success();
    }

    /**
     * 更新维修进度。
     * <p>报修单需处于已核验或维修中状态；进度为「已完成」时会额外提醒
     * 管理员及时验收。首次更新进度会顺带写入维修开始时间。
     *
     * @param id  报修单主键
     * @param dto 进度状态、说明、费用、预计时长与维修人
     * <p>进度说明会推送给报修居民；维修完成时还会提醒管理员验收。
     * @return 空结果
     */
    @PostMapping("/{id}/progress")
    public Result<Void> progress(@PathVariable Long id, @Valid @RequestBody DamageProgressDTO dto) {
        damageService.progress(id, dto);
        return Result.success();
    }

    /**
     * 验收维修结果。
     * <p>仅当报修单处于维修中且最新进度为「已完成」时才允许验收。
     * 验收结论会决定报修单终态，并联动更新工具状态、损坏等级与最近维修时间。
     *
     * @param id  报修单主键
     * @param dto 验收结果、验收后工具状态、维修费用与备注
     * <p>验收是报修流程的最后一步，验收后报修单进入终态，不再接受进度更新。
     * @return 空结果
     */
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id, @Valid @RequestBody DamageAcceptDTO dto) {
        damageService.accept(id, dto);
        return Result.success();
    }
}
