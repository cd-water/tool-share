package com.cdwater.toolshare.tool.vo;

import lombok.Data;

/**
 * 工具创建出参
 */
@Data
public class ToolCreateVO {

    private Long id;

    /** 自动生成的工具编号 SQ-G-XX-YYYY */
    private String toolCode;
}
