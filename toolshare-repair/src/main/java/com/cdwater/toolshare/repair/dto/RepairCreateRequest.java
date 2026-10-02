package com.cdwater.toolshare.repair.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class RepairCreateRequest {

    @NotNull(message = "工具不能为空")
    private Long toolId;

    /** 关联租赁单（租赁期间损坏） */
    private Long orderId;

    @NotBlank(message = "损坏部位不能为空")
    private String damagePart;

    @NotBlank(message = "损坏描述不能为空")
    private String description;

    /** 凭证图/视频 URL（§8 上传所得），1-3 个 */
    @Size(max = 3, message = "凭证最多 3 个")
    private List<String> imageUrls;
}
