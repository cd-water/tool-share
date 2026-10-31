package com.cdwater.toolshare.controller;

import com.cdwater.toolshare.common.Result;
import com.cdwater.toolshare.dto.DamageCreateDTO;
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
@RequestMapping("/api/user/damage-reports")
@RequiredArgsConstructor
public class UserDamageController {

    private final DamageService damageService;

    @GetMapping
    public Result<PageVO<DamageReportVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(damageService.userPage(page, size));
    }

    @GetMapping("/{id}")
    public Result<DamageReportVO> detail(@PathVariable Long id) {
        return Result.success(damageService.userDetail(id));
    }

    @PostMapping
    public Result<DamageReportVO> create(@Valid @RequestBody DamageCreateDTO dto) {
        return Result.success(damageService.create(dto));
    }
}
