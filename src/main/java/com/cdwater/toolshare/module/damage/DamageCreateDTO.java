package com.cdwater.toolshare.module.damage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DamageCreateDTO {

    @NotNull(message = "请选择关联租赁")
    private Long rentalId;

    @NotBlank(message = "请填写损坏部位")
    @Size(max = 200, message = "损坏部位不能超过 200 个字符")
    private String damagePart;

    @NotBlank(message = "请填写损坏描述")
    private String description;

    @Size(max = 3, message = "最多上传 3 张损坏照片")
    private List<@Size(max = 500, message = "图片地址不能超过 500 个字符") String> images;
}
