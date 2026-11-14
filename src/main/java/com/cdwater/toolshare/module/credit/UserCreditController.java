package com.cdwater.toolshare.module.credit;

import com.cdwater.toolshare.shared.web.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端信用分接口。
 *
 * <p>只暴露“查看本人信用分”这一读接口，不提供任何写入入口：分数变化
 * 只能由归还、验收、评价等业务流程在服务端触发。接口始终使用登录会话
 * 中的用户 ID，不接受前端传入用户参数，避免横向越权查询他人信用分。</p>
 *
 * <p>接口路径位于 {@code /api/user/**} 下，由 Sa-Token 用户端拦截器统一
 * 校验登录态；未登录请求不会进入本控制器。分页参数使用默认值 1/10，
 * 最终由 {@code PageQuery} 收敛到 1-100 的合法范围。</p>
 *
 * <p>响应统一包在 {@code Result} envelope 中，前端按 code 判断成功与否。
 * 本接口是只读的，幂等且无副作用，可安全刷新页面或重复请求。
 * 返回的流水已由服务层完成实体到 VO 的转换，控制器不再加工数据。</p>
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserCreditController {

    /** 信用分业务服务，负责分数读取与流水分页。 */
    private final CreditService creditService;

    /** 本人信用分与最近流水（分页）；不接受用户参数，始终查询登录用户。 */
    @GetMapping("/credit")
    public Result<CreditVO> credit(@RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "10") long size) {
        // 用户 ID 来自 Sa-Token 会话，page/size 由 PageQuery 再统一收敛。
        return Result.success(creditService.overview(page, size));
    }
}
