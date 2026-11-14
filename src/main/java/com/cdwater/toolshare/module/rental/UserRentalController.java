/**
 * 用户端租赁接口文件。
 * <p>路径：/api/user/rentals，用户只能查看和操作自己的租赁单。
 * <h2>接口</h2>
 * <ul>
 *   <li>GET /：分页查询自己的租赁；</li>
 *   <li>GET /{id}：详情，附带费用与流水；</li>
 *   <li>POST /：创建预约；</li>
 *   <li>POST /{id}/pay：支付押金；</li>
 *   <li>POST /{id}/cancel：取消预约。</li>
 * </ul>
 * <h2>业务限制</h2>
 * <p>创建预约要求实名信息完整；取消在取件前 24 小时内不可自助完成；
 * 支付需在待支付超时窗口内。所有权校验在 RentalService 内完成。
 */
package com.cdwater.toolshare.module.rental;

import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.rental.CancelDTO;
import com.cdwater.toolshare.module.rental.RentalCreateDTO;
import com.cdwater.toolshare.module.rental.RentalService;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.module.rental.RentalVO;
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
 * 用户端租赁接口。
 * <p>用户只能查看和操作自己的租赁单；所有权校验在 RentalService 内完成。
 * 取消受“取件前 24 小时”限制，支付需在待支付超时窗口内完成。
 */
@RestController
@RequestMapping("/api/user/rentals")
@RequiredArgsConstructor
public class UserRentalController {

    // 租赁业务逻辑集中委托给 RentalService
    private final RentalService rentalService;

    @GetMapping
    /**
     * 当前用户的租赁分页。
     * <p>关键字匹配单号、工具名称与工具编号；结果按创建时间倒序。
     *
     * @param keyword 关键字
     * @param status  状态编码
     * @param page    页码
     * @param size    每页条数
     * @return 租赁分页结果
     */
    public Result<PageVO<RentalVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(rentalService.userPage(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    /**
     * 当前用户的租赁详情，附带费用与资金流水。
     *
     * @param id 租赁单 id
     * @return 租赁详情
     */
    public Result<RentalVO> detail(@PathVariable Long id) {
        return Result.success(rentalService.userDetail(id));
    }

    @PostMapping
    /**
     * 创建租赁预约。
     * <p>要求用户已完善真实姓名与身份证；同一工具的并发预约由工具行锁串行化。
     *
     * @param dto 预约请求
     * @return 新建的租赁单
     */
    public Result<RentalVO> create(@Valid @RequestBody RentalCreateDTO dto) {
        return Result.success(rentalService.create(dto));
    }

    @PostMapping("/{id}/pay")
    /**
     * 支付押金，仅本人待支付且未超时的租赁单可支付。
     *
     * @param id 租赁单 id
     * @return 空成功响应
     */
    public Result<Void> pay(@PathVariable Long id) {
        rentalService.pay(id);
        return Result.success();
    }

    @PostMapping("/{id}/cancel")
    /**
     * 用户取消预约。
     * <p>取件前 24 小时内不允许自助取消，需联系社区工作人员。
     *
     * @param id  租赁单 id
     * @param dto 可选的取消原因
     * @return 空成功响应
     */
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) CancelDTO dto) {
        rentalService.cancelByUser(id, dto == null ? null : dto.getReason());
        return Result.success();
    }
}
