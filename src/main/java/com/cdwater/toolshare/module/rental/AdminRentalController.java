package com.cdwater.toolshare.module.rental;

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

@RestController
@RequestMapping("/api/admin/rentals")
@RequiredArgsConstructor
public class AdminRentalController {

    private final RentalService rentalService;

    @GetMapping
    public Result<PageVO<RentalVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String overdue,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(rentalService.adminPage(keyword, status, overdue, page, size));
    }

    @GetMapping("/{id}")
    public Result<RentalVO> detail(@PathVariable Long id) {
        return Result.success(rentalService.adminDetail(id));
    }

    @PostMapping("/{id}/pickup")
    public Result<Void> pickup(@PathVariable Long id) {
        rentalService.pickup(id);
        return Result.success();
    }

    @PostMapping("/{id}/return")
    public Result<Void> returnRental(@PathVariable Long id, @Valid @RequestBody RentalReturnDTO dto) {
        rentalService.returnRental(id, dto);
        return Result.success();
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) CancelDTO dto) {
        rentalService.cancelByAdmin(id, dto == null ? null : dto.getReason());
        return Result.success();
    }

    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String overdue,
            HttpServletResponse response) throws Exception {
        List<RentalExportRow> rows = rentalService.exportRows(keyword, status, overdue);
        String fileName = URLEncoder.encode("租赁记录.xlsx", StandardCharsets.UTF_8);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
        EasyExcel.write(response.getOutputStream(), RentalExportRow.class)
                .sheet("租赁记录")
                .doWrite(rows);
    }
}
