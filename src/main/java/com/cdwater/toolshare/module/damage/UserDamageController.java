package com.cdwater.toolshare.module.damage;

import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.damage.DamageCreateDTO;
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
 * 居民端损坏报修控制器。
 * <p>提供提交报修、查看本人报修列表与详情三类接口，统一挂载在
 * {@code /api/user/damage-reports} 下。所有查询与写入都以当前会话用户
 * <p>接口不直接暴露状态推进能力：居民只能提交报修与查看结果，
 * 核验、维修与验收全部由管理端接口完成，权限边界清晰。
 * 为归属，用户 ID 不通过请求参数传入，避免越权访问他人报修。
 */
@RestController
@RequestMapping("/api/user/damage-reports")
@RequiredArgsConstructor
public class UserDamageController {
    // 居民端只暴露提交、列表与详情三类接口。
    // 用户身份完全取自会话。
    // 状态推进全部由管理端完成。

    /** 报修领域服务。 */
    private final DamageService damageService;

    /**
     * 分页查询当前居民的报修单。
     * <p>关键字匹配报修单号或工具编号；状态为空时返回全部状态。
     *
     * @param keyword 可选模糊关键字
     * @param status  可选报修状态编码
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return 当前居民的报修单分页结果
     * <p>列表项包含工具主图与状态中文名，前端可直接渲染卡片。
     */
    @GetMapping
    public Result<PageVO<DamageReportVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(damageService.userPage(keyword, status, page, size));
    }

    /**
     * 查看当前居民的报修单详情。
     * <p>服务层会校验报修单归属，非本人报修返回 403。
     *
     * @param id 报修单主键
     * @return 报修单详情
     * <p>非本人报修返回 403，与「报修单不存在」的 404 区分开。
     */
    @GetMapping("/{id}")
    public Result<DamageReportVO> detail(@PathVariable Long id) {
        return Result.success(damageService.userDetail(id));
    }

    /**
     * 提交一条新的损坏报修。
     * <p>仅使用中的租赁可报修，且同一租赁不能同时存在处理中的报修单。
     *
     * @param dto 报修参数
     * @return 新建报修单的视图对象
     * <p>成功后会在事务提交后异步通知全部管理员，居民端立即看到待核验状态。
     */
    @PostMapping
    public Result<DamageReportVO> create(@Valid @RequestBody DamageCreateDTO dto) {
        return Result.success(damageService.create(dto));
    }
}
