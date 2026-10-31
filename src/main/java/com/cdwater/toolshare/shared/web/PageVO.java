package com.cdwater.toolshare.shared.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

@Data
public class PageVO<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;

    public static <S, T> PageVO<T> of(IPage<S> source, Function<S, T> mapper) {
        PageVO<T> vo = new PageVO<>();
        vo.setRecords(source.getRecords().stream().map(mapper).toList());
        vo.setTotal(source.getTotal());
        vo.setPage(source.getCurrent());
        vo.setSize(source.getSize());
        return vo;
    }

    public static <T> PageVO<T> of(List<T> records, long total, long page, long size) {
        PageVO<T> vo = new PageVO<>();
        vo.setRecords(records);
        vo.setTotal(total);
        vo.setPage(page);
        vo.setSize(size);
        return vo;
    }
}
