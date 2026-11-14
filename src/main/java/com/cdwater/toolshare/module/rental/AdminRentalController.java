/**
 * 管理端租赁接口文件。
 * <p>路径：/api/admin/rentals，受 {@code rental:manage} 权限保护。
 * <h2>接口</h2>
 * <ul>
 *   <li>GET /：分页，支持关键字、状态、逾期与验收结果筛选；</li>
 *   <li>GET /{id}：详情，附带费用与资金流水；</li>
 *   <li>POST /{id}/pickup：确认取件；</li>
 *   <li>POST /{id}/return：归还验收与结算；</li>
 *   <li>POST /{id}/cancel：管理员取消；</li>
 *   <li>GET /export：导出 Excel。</li>
 * </ul>
 * <h2>边界</h2>
 * <p>金额计算、状态机与并发控制全部在 RentalService；
 * 导出文件名使用 URL 编码，内容中的手机号与身份证已脱敏。
 */
package com.cdwater.toolshare.module.rental;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alibaba.excel.EasyExcel;
import com.cdwater.toolshare.shared.web.Result;
import com.cdwater.toolshare.module.rental.CancelDTO;
import com.cdwater.toolshare.module.rental.RentalReturnDTO;
import com.cdwater.toolshare.module.rental.RentalService;
import com.cdwater.toolshare.shared.web.PageVO;
import com.cdwater.toolshare.module.rental.RentalExportRow;
import com.cdwater.toolshare.module.rental.RentalVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 管理端租赁接口。
 * <p>覆盖分页、详情、取件、归还验收、取消与 Excel 导出，受 {@code rental:manage} 权限保护。
 * 金额计算、状态机校验和并发控制全部在 RentalService，控制器只做参数绑定与文件输出。
 */
@RestController
@RequestMapping("/api/admin/rentals")
@RequiredArgsConstructor
@SaCheckPermission(type = "admin", value = "rental:manage")
public class AdminRentalController {

    // 所有租赁业务逻辑都在 RentalService
    private final RentalService rentalService;

    @GetMapping
    /**
     * 管理端租赁分页。
     * <p>keyword 匹配单号、工具编号/名称、租用人/手机号；
     * overdue 控制逾期过滤，damageResult 控制验收结果筛选。
     *
     * @param keyword      关键字
     * @param status       状态编码
     * @param overdue      逾期过滤：true/false/all
     * @param damageResult 验收结果编码
     * @param page         页码
     * @param size         每页条数
     * @return 租赁分页结果
     */
    public Result<PageVO<RentalVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String overdue,
            @RequestParam(required = false) String damageResult,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(rentalService.adminPage(keyword, status, overdue, damageResult,
                page, size));
    }

    @GetMapping("/{id}")
    /**
     * 管理端租赁详情，附带费用明细与资金流水。
     *
     * @param id 租赁单 id
     * @return 租赁详情
     */
    public Result<RentalVO> detail(@PathVariable Long id) {
        return Result.success(rentalService.adminDetail(id));
    }

    @PostMapping("/{id}/pickup")
    /**
     * 确认取件：租赁单转使用中，工具转已租出。
     *
     * @param id 租赁单 id
     * @return 空成功响应
     */
    public Result<Void> pickup(@PathVariable Long id) {
        rentalService.pickup(id);
        return Result.success();
    }

    @PostMapping("/{id}/return")
    /**
     * 归还验收并结算。
     * <p>请求只提供业务事实（归还时间、验收结果、减免标志与原因），
     * 金额由服务端计算，不能由请求体覆盖。
     *
     * @param id  租赁单 id
     * @param dto 归还验收请求
     * @return 空成功响应
     */
    public Result<Void> returnRental(@PathVariable Long id, @Valid @RequestBody RentalReturnDTO dto) {
        rentalService.returnRental(id, dto);
        return Result.success();
    }

    @PostMapping("/{id}/cancel")
    /**
     * 管理员取消租赁。
     * <p>请求体可选，未提供原因时使用“工作人员取消”。
     *
     * @param id  租赁单 id
     * @param dto 可选的取消原因
     * @return 空成功响应
     */
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) CancelDTO dto) {
        rentalService.cancelByAdmin(id, dto == null ? null : dto.getReason());
        return Result.success();
    }

    @GetMapping("/export")
    /**
     * 导出租赁单 Excel。
     * <p>筛选口径与列表一致；文件名使用 UTF-8 URL 编码，避免中文乱码。
     * 导出内容中的手机号与身份证为脱敏值，不包含完整证件信息。
     *
     * @param keyword      关键字
     * @param status       状态编码
     * @param overdue      逾期过滤
     * @param damageResult 验收结果
     * @param response     HTTP 响应，直接写入 xlsx 字节流
     * @throws Exception 写入响应流失败时抛出
     */
    public void export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String overdue,
            @RequestParam(required = false) String damageResult,
            HttpServletResponse response) throws Exception {
        // 先一次性构造全部导出行，再写文件；数据量受筛选条件约束
        List<RentalExportRow> rows = rentalService.exportRows(keyword, status, overdue,
                damageResult);
        // 中文文件名必须 URL 编码，浏览器才能正确还原
        String fileName = URLEncoder.encode("租赁单.xlsx", StandardCharsets.UTF_8);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
        // 表头来自 RentalExportRow 的 @ExcelProperty 注解
        EasyExcel.write(response.getOutputStream(), RentalExportRow.class)
                .sheet("租赁单")
                .doWrite(rows);
    }
}
