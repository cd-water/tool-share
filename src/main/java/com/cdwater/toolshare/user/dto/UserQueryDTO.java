package com.cdwater.toolshare.user.dto;

import com.cdwater.toolshare.common.dto.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询入参
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryDTO extends PageQuery {

    /** 真实姓名，模糊 */
    @Size(max = 50, message = "姓名最长 50 字")
    private String realName;

    /** 角色：0居民 1社区工作人员 2系统管理员 */
    @Min(value = 0, message = "角色取值不正确")
    @Max(value = 2, message = "角色取值不正确")
    private Integer role;
}
