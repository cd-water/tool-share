package com.cdwater.toolshare.shared.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应结构。
 *
 * <p>与 MyBatis-Plus 的 {@link IPage} 解耦，只暴露前端需要的四个字段：
 * 当前页记录、总数、页码与每页条数。实体到 VO 的转换通过映射函数在
 * 构造时完成，避免把持久化对象直接返回给接口调用方。</p>
 *
 * @param <T> 记录类型
 */
@Data
public class PageVO<T> {

    /** 当前页记录列表，已转换为视图对象。 */
    private List<T> records;

    /** 满足条件的总记录数，用于计算总页数。 */
    private long total;

    /** 当前页码，从 1 开始。 */
    private long page;

    /** 每页条数，已由 PageQuery 校正。 */
    private long size;

    /**
     * 从持久化分页对象转换。
     *
     * <p>逐条应用映射函数，再复制分页元数据。映射函数不应访问数据库，
     * 否则会重新引入 N+1 查询。</p>
     *
     * @param source 持久化分页结果
     * @param mapper 实体到视图的映射函数
     * @param <S>    实体类型
     * @param <T>    视图类型
     * @return 分页视图
     */
    public static <S, T> PageVO<T> of(IPage<S> source, Function<S, T> mapper) {
        PageVO<T> vo = new PageVO<>();
        // 映射在内存中完成，保持查询次数不变。
        vo.setRecords(source.getRecords().stream().map(mapper).toList());
        vo.setTotal(source.getTotal());
        vo.setPage(source.getCurrent());
        vo.setSize(source.getSize());
        return vo;
    }

    /**
     * 从已转换的列表直接构造分页视图。
     *
     * @param records 当前页记录
     * @param total   总记录数
     * @param page    当前页码
     * @param size    每页条数
     * @param <T>     记录类型
     * @return 分页视图
     */
    public static <T> PageVO<T> of(List<T> records, long total, long page, long size) {
        PageVO<T> vo = new PageVO<>();
        vo.setRecords(records);
        vo.setTotal(total);
        vo.setPage(page);
        vo.setSize(size);
        return vo;
    }
}
