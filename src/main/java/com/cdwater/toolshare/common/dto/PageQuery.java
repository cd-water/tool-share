package com.cdwater.toolshare.common.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页查询基类（GET 查询参数绑定）
 */
@Data
public class PageQuery {

    /** 页码，从 1 开始 */
    @Min(value = 1, message = "页码最小为 1")
    private long page = 1;

    /** 每页条数 */
    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private long size = 10;

    /**
     * 转为 MyBatis-Plus 分页对象
     */
    public <T> Page<T> toPage() {
        return new Page<>(page, size);
    }
}
