package com.cdwater.toolshare.shared.storage;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 上传成功后返回给前端的文件信息。
 *
 * <p>url 可能是对象存储直链，也可能是后端 {@code /api/files/**} 代理地址，
 * 取决于桶公开读是否启用；前端应把 url 当作不透明字符串直接使用，
 * 不要自行拼接存储域名。</p>
 */
@Data
@AllArgsConstructor
public class FileVO {

    /** 文件可访问地址，已由服务端根据存储配置选择直链或代理。 */
    private String url;

    /** 服务端生成的安全文件名，已去除路径与非法字符。 */
    private String name;
}
