/**
 * 工具新增/编辑请求体文件。
 * <p>name、category、damageLevel、deposit 必填；
 * status 可选：新增时为空默认 AVAILABLE，编辑时非空会触发状态机。
 * <h2>字段处理</h2>
 * <ul>
 *   <li>可选文本在服务层 trim 后落库，null 归一为空串；</li>
 *   <li>usageGuide 保存前经过 HTML 清洗；</li>
 *   <li>imageUrls 拼接为逗号分隔串，第一张作为主图。</li>
 * </ul>
  * <h2>字段级语义</h2>
  * <p>name 必填并 trim；model/storageArea/supplier 可选并归一为空串；
  * category 必填且必须命中 Dict.TOOL_CATEGORY；
  * damageLevel 必填且必须命中 Dict.DAMAGE_LEVEL；
  * deposit 必填且不能为负；
  * status 可选，传入时必须命中 Dict.TOOL_STATUS，且编辑时会触发状态机。
  * <p>usageGuide 没有长度注解，但落库前统一清洗；imageUrls 每个地址最长 500。
 * <h2>校验边界</h2>
 * <p>@Schema 只用于文档展示，类别/损坏程度/状态仍由服务层按字典再次校验。
 * <p>调用方：AdminToolController#create/update。
 */
package com.cdwater.toolshare.module.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 工具新增/编辑请求体。
 * <p>name、category、damageLevel、deposit 必填；
 * status 可选：新增时为空默认 AVAILABLE，编辑时若非空会触发状态机校验。
 * 图片以列表传入，服务层拼接为逗号分隔字符串并把第一张作为主图。
 */
@Data
public class ToolSaveDTO {

    @NotBlank(message = "工具名称不能为空")
    @Size(max = 100, message = "工具名称不能超过 100 个字符")
    // 工具名称，必填，最长 100 字
    private String name;

    @Size(max = 200, message = "型号规格不能超过 200 个字符")
    // 型号规格，可选
    // 型号规格，可选
    private String model;

    @NotBlank(message = "工具类别不能为空")
    @Size(max = 16, message = "工具类别不能超过 16 个字符")
    @Schema(allowableValues = {"WX", "YY", "QJ", "QT"})
    // 工具类别，必填，必须是字典中的合法编码
    private String category;

    @NotBlank(message = "损坏程度不能为空")
    @Size(max = 16, message = "损坏程度不能超过 16 个字符")
    @Schema(allowableValues = {"NEW", "LIGHT", "MEDIUM", "SEVERE"})
    // 损坏程度，必填，必须是字典中的合法编码
    private String damageLevel;

    @NotNull(message = "押金不能为空")
    @DecimalMin(value = "0", message = "押金不能为负数")
    // 押金，必填且不能为负数；租赁创建时快照
    private BigDecimal deposit;

    @Size(max = 200, message = "存放区域不能超过 200 个字符")
    // 存放区域，可选
    // 存放区域，可选
    private String storageArea;

    // 使用说明，可为 HTML 或 JSON 步骤数组，落库前统一清洗
    // 使用说明，可为 HTML 或 JSON 步骤数组
    private String usageGuide;

    // 图片地址列表，第一张作为列表主图
    private List<@Size(max = 500, message = "图片地址不能超过 500 个字符") String> imageUrls;

    // 采购日期，可选
    // 采购日期，可选
    private LocalDate purchaseDate;

    @Size(max = 200, message = "供应商不能超过 200 个字符")
    // 供应商，可选
    // 供应商，可选
    private String supplier;

    @Size(max = 20, message = "工具状态不能超过 20 个字符")
    @Schema(allowableValues = {"AVAILABLE", "RENTED", "MAINTENANCE", "SCRAPPED"})
    // 可选状态；编辑时变更会走状态机，新增时为空默认可用
    private String status;
}
