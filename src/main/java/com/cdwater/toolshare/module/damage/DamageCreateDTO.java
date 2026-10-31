package com.cdwater.toolshare.module.damage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class DamageCreateDTO {

    @NotNull(message = "请选择关联租赁")
    private Long rentalId;

    @NotBlank(message = "请填写损坏部位")
    private String damagePart;

    @NotBlank(message = "请填写损坏描述")
    private String description;

    private List<String> images;
}
