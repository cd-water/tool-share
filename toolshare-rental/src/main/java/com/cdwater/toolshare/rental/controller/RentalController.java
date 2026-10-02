package com.cdwater.toolshare.rental.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.cdwater.toolshare.common.result.PageResult;
import com.cdwater.toolshare.common.result.Result;
import com.cdwater.toolshare.rental.dto.PayResponse;
import com.cdwater.toolshare.rental.dto.PickupRequest;
import com.cdwater.toolshare.rental.dto.RentalCreateRequest;
import com.cdwater.toolshare.rental.dto.RentalCreatedResponse;
import com.cdwater.toolshare.rental.dto.RentalOrderItem;
import com.cdwater.toolshare.rental.dto.RentalQuery;
import com.cdwater.toolshare.rental.dto.ReturnRequest;
import com.cdwater.toolshare.rental.dto.SettleResponse;
import com.cdwater.toolshare.rental.dto.SuggestionResponse;
import com.cdwater.toolshare.rental.service.RentalService;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rental/orders")
public class RentalController {

    private final RentalService rentalService;

    private static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss";

    @SaCheckRole("RESIDENT")
    @PostMapping
    public Result<RentalCreatedResponse> create(@Valid @RequestBody RentalCreateRequest request) {
        return Result.success(rentalService.create(request));
    }

    @SaCheckRole("RESIDENT")
    @GetMapping("/suggestion")
    public Result<SuggestionResponse> suggestion(@RequestParam Long toolId,
                                                  @RequestParam @DateTimeFormat(pattern = DATE_TIME) LocalDateTime startTime,
                                                  @RequestParam @DateTimeFormat(pattern = DATE_TIME) LocalDateTime endTime) {
        return Result.success(rentalService.suggestion(toolId, startTime, endTime));
    }

    @SaCheckRole("RESIDENT")
    @PostMapping("/{id}/pay")
    public Result<PayResponse> pay(@PathVariable Long id) {
        return Result.success(rentalService.pay(id));
    }

    @SaCheckRole("RESIDENT")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        rentalService.cancel(id);
        return Result.success();
    }

    @SaCheckRole("STAFF")
    @PostMapping("/{id}/pickup")
    public Result<Void> pickup(@PathVariable Long id, @Valid @RequestBody PickupRequest request) {
        rentalService.pickup(id, request);
        return Result.success();
    }

    @SaCheckRole("STAFF")
    @PostMapping("/{id}/return")
    public Result<SettleResponse> returnOrder(@PathVariable Long id, @Valid @RequestBody ReturnRequest request) {
        return Result.success(rentalService.returnOrder(id, request));
    }

    @SaCheckRole("RESIDENT")
    @GetMapping("/my")
    public Result<PageResult<RentalOrderItem>> my(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) String status) {
        return Result.success(rentalService.my(page, size, status));
    }

    @SaCheckRole(value = {"STAFF", "ADMIN"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<RentalOrderItem>> page(RentalQuery query) {
        return Result.success(rentalService.page(query));
    }

    @SaCheckRole(value = {"STAFF", "ADMIN"}, mode = SaMode.OR)
    @GetMapping("/export")
    public Result<byte[]> export(RentalQuery query) {
        return Result.success(rentalService.export(query));
    }
}
