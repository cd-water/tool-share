package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.dto.DamageAcceptDTO;
import com.cdwater.toolshare.dto.DamageProgressDTO;
import com.cdwater.toolshare.dto.DamageVerifyDTO;
import com.cdwater.toolshare.service.DamageService;
import com.cdwater.toolshare.vo.DamageReportVO;
import com.cdwater.toolshare.vo.PageVO;
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
@RequestMapping("/api/admin/damage-reports")
@RequiredArgsConstructor
public class AdminDamageController {

    private final DamageService damageService;

    @GetMapping
    public Result<PageVO<DamageReportVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(damageService.adminPage(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    public Result<DamageReportVO> detail(@PathVariable Long id) {
        return Result.success(damageService.adminDetail(id));
    }

    @PostMapping("/{id}/verify")
    public Result<Void> verify(@PathVariable Long id, @Valid @RequestBody DamageVerifyDTO dto) {
        damageService.verify(id, dto);
        return Result.success();
    }

    @PostMapping("/{id}/progress")
    public Result<Void> progress(@PathVariable Long id, @Valid @RequestBody DamageProgressDTO dto) {
        damageService.progress(id, dto);
        return Result.success();
    }

    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id, @Valid @RequestBody DamageAcceptDTO dto) {
        damageService.accept(id, dto);
        return Result.success();
    }
}
