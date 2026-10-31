package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.dto.CancelDTO;
import com.cdwater.toolshare.dto.RentalCreateDTO;
import com.cdwater.toolshare.service.RentalService;
import com.cdwater.toolshare.vo.PageVO;
import com.cdwater.toolshare.vo.RentalVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/rentals")
@RequiredArgsConstructor
public class UserRentalController {

    private final RentalService rentalService;

    @GetMapping
    public Result<PageVO<RentalVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(rentalService.userPage(status, page, size));
    }

    @GetMapping("/{id}")
    public Result<RentalVO> detail(@PathVariable Long id) {
        return Result.success(rentalService.userDetail(id));
    }

    @PostMapping
    public Result<RentalVO> create(@Valid @RequestBody RentalCreateDTO dto) {
        return Result.success(rentalService.create(dto));
    }

    @PostMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id) {
        rentalService.pay(id);
        return Result.success();
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) CancelDTO dto) {
        rentalService.cancelByUser(id, dto == null ? null : dto.getReason());
        return Result.success();
    }
}
