package com.cdwater.toolshare.file.vo;

import lombok.Data;

/**
 * 文件上传出参
 */
@Data
public class FileVO {

    /** 可访问的文件 URL（MinIO） */
    private String url;
}
