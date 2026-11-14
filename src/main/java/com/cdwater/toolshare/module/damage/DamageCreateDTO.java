package com.cdwater.toolshare.module.damage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
/**
 * <p>报修是维修流程的入口：居民在工具使用过程中发现异常后提交，
 * 管理端据此核验、安排维修并最终验收。报修单会冗余保存租赁与工具
 * 快照，即使后续租赁归还、工具改名，也不影响历史记录的可读性。
 * 居民提交损坏报修的请求参数。
 * <p>报修必须关联一条仍在使用中的租赁记录；同一租赁同时只允许存在一张
 * 处理中的报修单，该约束由服务层与数据库唯一索引共同保证。
 */
public class DamageCreateDTO {

    /** 关联的租赁单主键，服务层会校验其归属与当前状态。 */
    // 报修接口不信任前端传入的用户归属，租赁校验同时覆盖租赁人与当前会话。
    @NotNull(message = "请选择关联租赁")
    private Long rentalId;

    /** 损坏部位描述，例如「刀片」「把手」，最多 200 个字符。 */
    // 该字段用于快速定位问题，管理端列表与通知文案会直接引用。
    @NotBlank(message = "请填写损坏部位")
    @Size(max = 200, message = "损坏部位不能超过 200 个字符")
    private String damagePart;

    /** 损坏情况的具体描述，用于管理员核验。 */
    // 描述越具体，越容易判断是否需要上门维修或返厂处理。
    @NotBlank(message = "请填写损坏描述")
    private String description;

    /**
     * 损坏照片地址列表，最多 3 张；保存时以逗号拼接进数据库。
     * <p>图片只保存对象存储地址，前端负责上传；服务端仅校验数量与长度。
     */
    @Size(max = 3, message = "最多上传 3 张损坏照片")
    private List<@Size(max = 500, message = "图片地址不能超过 500 个字符") String> images;
}
