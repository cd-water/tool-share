package com.cdwater.toolshare.module.tool;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.cdwater.toolshare.module.rental.RentalService;
import com.cdwater.toolshare.module.rental.RentalVO;
import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.tool.ToolAuditDTO;
import com.cdwater.toolshare.module.tool.ToolRestoreDTO;
import com.cdwater.toolshare.module.tool.ToolSaveDTO;
import com.cdwater.toolshare.module.tool.ToolStatusDTO;
import com.cdwater.toolshare.module.tool.ToolService;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.module.tool.ToolLogVO;
import com.cdwater.toolshare.module.tool.ToolVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端工具接口。
 * <p>覆盖工具分页、新增、编辑、审核、状态修改、报废恢复、历史租赁与操作日志。
 * 整个控制器受 {@code tool:manage} 权限保护，审核与恢复仅系统管理员实际可操作。
 * <p>与租赁模块的关系：历史租赁查询直接复用 RentalService 的分页与批量映射，
 * 避免在工具侧重复实现租赁展示逻辑。
 */
@RestController
@RequestMapping("/api/admin/tools")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "tool:manage")
public class AdminToolController {

    // 工具生命周期与状态机逻辑全部在 ToolService
    private final ToolService toolService;
    // 工具历史租赁复用租赁服务的分页查询
    private final RentalService rentalService;

    @GetMapping
    /**
     * 管理端工具分页查询。
     * <p>支持关键字、类别、状态、存放区域筛选，以及按租赁次数/最近维修时间排序；
     * 未审核工具也会出现在管理端列表中。
     *
     * @param query 查询条件与分页参数
     * @return 工具分页结果
     */
    public Result<PageVO<ToolVO>> list(ToolAdminQuery query) {
        return Result.success(toolService.adminPage(query));
    }

    @PostMapping
    /**
     * 新增工具。
     * <p>新工具默认进入待审核状态，编号按类别自动生成；
     * 创建成功后尝试写入向量索引，失败不影响主流程。
     *
     * @param dto 工具属性
     * @return 新建工具视图
     */
    public Result<ToolVO> create(@Valid @RequestBody ToolSaveDTO dto) {
        return Result.success(toolService.create(dto));
    }

    @PutMapping("/{id}")
    /**
     * 编辑工具属性。
     * <p>若请求同时修改 status，会走状态机校验；否则仅更新静态属性并同步索引。
     *
     * @param id  工具 id
     * @param dto 工具属性
     * @return 更新后的工具视图
     */
    public Result<ToolVO> update(@PathVariable Long id, @Valid @RequestBody ToolSaveDTO dto) {
        return Result.success(toolService.update(id, dto));
    }

    @PatchMapping("/{id}/audit")
    /**
     * 审核工具。
     * <p>只允许通过或退回，不能改回待审核；退回必须填写审核意见。
     *
     * @param id  工具 id
     * @param dto 审核结果与意见
     * @return 审核后的工具视图
     */
    public Result<ToolVO> audit(@PathVariable Long id, @Valid @RequestBody ToolAuditDTO dto) {
        return Result.success(toolService.audit(id, dto));
    }

    @PatchMapping("/{id}/status")
    /**
     * 手动修改工具状态。
     * <p>仍受 ToolStateMachine 约束，且置为可用前会检查活动租赁与未完成报修。
     *
     * @param id  工具 id
     * @param dto 目标状态
     * @return 空成功响应
     */
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody ToolStatusDTO dto) {
        toolService.updateStatus(id, dto.getStatus());
        return Result.success();
    }

    /** 暴露式恢复已报废工具：必须填写原因，存在活动租赁/未完成报修时拒绝。 */
    @PostMapping("/{id}/restore")
    /**
     * 恢复已报废工具。
     * <p>必须填写原因；存在活动租赁或未完成报修时拒绝恢复。
     *
     * @param id  工具 id
     * @param dto 恢复原因
     * @return 恢复后的工具视图
     */
    public Result<ToolVO> restore(@PathVariable Long id, @Valid @RequestBody ToolRestoreDTO dto) {
        return Result.success(toolService.restore(id, dto));
    }

    /** 工具历史租赁：复用管理端租赁分页与批量映射。 */
    @GetMapping("/{id}/rentals")
    /**
     * 查询指定工具的历史租赁。
     * <p>复用租赁服务的批量映射，工具不存在时返回 404。
     *
     * @param id   工具 id
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 租赁分页结果
     */
    public Result<PageVO<RentalVO>> rentals(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        return Result.success(rentalService.toolRentalPage(id, page, size));
    }

    @GetMapping("/{id}/logs")
    /**
     * 查询工具操作日志，按创建时间倒序。
     *
     * @param id 工具 id
     * @return 操作日志列表
     */
    public Result<List<ToolLogVO>> logs(@PathVariable Long id) {
        return Result.success(toolService.logs(id));
    }
}
