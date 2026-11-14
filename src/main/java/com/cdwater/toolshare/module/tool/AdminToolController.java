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

@RestController
@RequestMapping("/api/admin/tools")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "tool:manage")
public class AdminToolController {

    private final ToolService toolService;
    private final RentalService rentalService;

    @GetMapping
    public Result<PageVO<ToolVO>> list(ToolAdminQuery query) {
        return Result.success(toolService.adminPage(query));
    }

    @PostMapping
    public Result<ToolVO> create(@Valid @RequestBody ToolSaveDTO dto) {
        return Result.success(toolService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<ToolVO> update(@PathVariable Long id, @Valid @RequestBody ToolSaveDTO dto) {
        return Result.success(toolService.update(id, dto));
    }

    @PatchMapping("/{id}/audit")
    public Result<ToolVO> audit(@PathVariable Long id, @Valid @RequestBody ToolAuditDTO dto) {
        return Result.success(toolService.audit(id, dto));
    }

    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody ToolStatusDTO dto) {
        toolService.updateStatus(id, dto.getStatus());
        return Result.success();
    }

    /** 暴露式恢复已报废工具：必须填写原因，存在活动租赁/未完成报修时拒绝。 */
    @PostMapping("/{id}/restore")
    public Result<ToolVO> restore(@PathVariable Long id, @Valid @RequestBody ToolRestoreDTO dto) {
        return Result.success(toolService.restore(id, dto));
    }

    /** 工具历史租赁：复用管理端租赁分页与批量映射。 */
    @GetMapping("/{id}/rentals")
    public Result<PageVO<RentalVO>> rentals(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        return Result.success(rentalService.toolRentalPage(id, page, size));
    }

    @GetMapping("/{id}/logs")
    public Result<List<ToolLogVO>> logs(@PathVariable Long id) {
        return Result.success(toolService.logs(id));
    }
}
