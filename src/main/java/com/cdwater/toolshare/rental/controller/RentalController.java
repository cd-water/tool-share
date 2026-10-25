package com.cdwater.toolshare.rental.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.cdwater.toolshare.common.constant.RoleConstant;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.rental.dto.RentalCreateDTO;
import com.cdwater.toolshare.rental.dto.RentalPickupDTO;
import com.cdwater.toolshare.rental.dto.RentalQueryDTO;
import com.cdwater.toolshare.rental.dto.RentalReturnDTO;
import com.cdwater.toolshare.rental.service.RentalService;
import com.cdwater.toolshare.rental.vo.RentalCreateVO;
import com.cdwater.toolshare.rental.vo.RentalOrderVO;
import com.cdwater.toolshare.rental.vo.TimeSlotVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;

/**
 * 租赁接口（预约/取件/归还/记录）
 */
@Validated
@RestController
@RequestMapping("/api/rental")
@RequiredArgsConstructor
public class RentalController {

    private final RentalService rentalService;

    /** 查询工具可预约时段 */
    @SaCheckRole(RoleConstant.RESIDENT)
    @GetMapping("/available-times")
    public Result<List<TimeSlotVO>> availableTimes(@RequestParam @NotNull(message = "工具不能为空") Long toolId,
                                                   @RequestParam @NotNull(message = "日期不能为空") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(rentalService.availableTimes(toolId, date));
    }

    /** 创建预约（时段冲突校验） */
    @SaCheckRole(RoleConstant.RESIDENT)
    @PostMapping
    public Result<RentalCreateVO> create(@Valid @RequestBody RentalCreateDTO dto) {
        return Result.success(rentalService.create(dto));
    }

    /** 押金缴纳（支付沙箱） */
    @SaCheckRole(RoleConstant.RESIDENT)
    @PostMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id) {
        rentalService.pay(id);
        return Result.success();
    }

    /** 取消预约（取件前 24 小时） */
    @SaCheckRole(RoleConstant.RESIDENT)
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        rentalService.cancel(id);
        return Result.success();
    }

    /** 取件登记 */
    @SaCheckRole(RoleConstant.STAFF)
    @PostMapping("/{id}/pickup")
    public Result<Void> pickup(@PathVariable Long id, @RequestBody(required = false) RentalPickupDTO dto) {
        rentalService.pickup(id, dto);
        return Result.success();
    }

    /** 归还验收 */
    @SaCheckRole(RoleConstant.STAFF)
    @PostMapping("/{id}/return")
    public Result<Void> returnTool(@PathVariable Long id, @RequestBody RentalReturnDTO dto) {
        rentalService.returnTool(id, dto);
        return Result.success();
    }

    /** 租赁记录分页（居民=本人，工作人员全量筛选） */
    @GetMapping("/page")
    public Result<PageResult<RentalOrderVO>> page(@Valid RentalQueryDTO query) {
        return Result.success(rentalService.page(query));
    }

    /** 租赁单详情 */
    @GetMapping("/{id}")
    public Result<RentalOrderVO> detail(@PathVariable Long id) {
        return Result.success(rentalService.detail(id));
    }

    /** 导出租赁记录 Excel（文件流响应） */
    @SaCheckRole(RoleConstant.STAFF)
    @GetMapping("/export")
    public void export(@Valid RentalQueryDTO query, HttpServletResponse response) {
        rentalService.export(query, response);
    }
}
