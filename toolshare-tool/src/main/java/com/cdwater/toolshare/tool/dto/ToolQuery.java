package com.cdwater.toolshare.tool.dto;

import lombok.Data;

/**
 * 工具分页筛选（居民：keyword/categoryId；员工可加 damageLevel/status/storageArea）
 */
@Data
public class ToolQuery {

    private long page = 1;

    private long size = 10;

    private String keyword;

    private Long categoryId;

    private String damageLevel;

    private String status;

    private String storageArea;
}
