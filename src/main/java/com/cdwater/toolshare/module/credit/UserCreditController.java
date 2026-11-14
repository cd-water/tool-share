package com.cdwater.toolshare.module.credit;

import com.cdwater.toolshare.shared.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserCreditController {

    private final CreditService creditService;

    /** 本人信用分与最近流水（分页）；不接受用户参数，始终查询登录用户。 */
    @GetMapping("/credit")
    public Result<CreditVO> credit(@RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "10") long size) {
        return Result.success(creditService.overview(page, size));
    }
}
