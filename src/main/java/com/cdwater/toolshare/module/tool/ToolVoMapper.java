/**
 * 工具实体到视图的 MapStruct 映射文件。
 * <p>基础字段自动映射；categoryName、damageLevelName、statusName、
 * auditStatusName 与 imageUrls 在 enrich 后置处理中补齐。
  * <h2>映射细节</h2>
  * <p>category/damageLevel/status 通过 Dict 翻译为中文名；
  * auditStatus 通过 AuditStatus.fromCode 翻译，未知值返回 null；
  * imageUrls 从逗号分隔串解析，过滤空白项。
  * <p>createdByName 与评分统计依赖其他表，由 ToolService 批量填充。
  * <p>映射器不访问数据库、不做业务校验，保证可在纯单元测试中使用。
 * <h2>职责边界</h2>
 * <p>createdByName 与评分统计依赖其他表，由 ToolService 批量填充，
 * 映射器保持无数据库访问。
 * <p>调用方：ToolService#toVO/toPage。
 */
package com.cdwater.toolshare.module.tool;

import com.cdwater.toolshare.shared.enums.AuditStatus;
import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 工具实体到视图的 MapStruct 映射。
 * <p>字典名称、图片列表与审核状态名称在 {@code enrich} 后置处理中补齐；
 * createdByName 与评分由 ToolService 另行批量填充。
 */
@Mapper(componentModel = "spring")
public interface ToolVoMapper {

    @Mapping(target = "categoryName", ignore = true)
    @Mapping(target = "damageLevelName", ignore = true)
    @Mapping(target = "statusName", ignore = true)
    @Mapping(target = "imageUrls", ignore = true)
    @Mapping(target = "auditStatusName", ignore = true)
    @Mapping(target = "createdByName", ignore = true)
    /**
     * 基础字段映射。
     *
     * @param tool 工具实体
     * @return 工具视图，字典名称等字段待后置处理
     */
    ToolVO toVO(Tool tool);

    // 后置映射：把字典编码翻译为展示名称，并把逗号分隔的图片串解析为列表
    @AfterMapping
    default void enrich(Tool tool, @MappingTarget ToolVO vo) {
        // 类别编码翻译为展示名
        vo.setCategoryName(Dict.label(Dict.TOOL_CATEGORY, tool.getCategory()));
        // 损坏程度翻译
        vo.setDamageLevelName(Dict.label(Dict.DAMAGE_LEVEL, tool.getDamageLevel()));
        // 工具状态翻译
        vo.setStatusName(Dict.label(Dict.TOOL_STATUS, tool.getStatus()));
        // 审核状态未知时返回 null
        vo.setAuditStatusName(AuditStatus.fromCode(tool.getAuditStatus())
                .map(AuditStatus::label).orElse(null));
        // 解析逗号分隔图片列表
        vo.setImageUrls(parseImages(tool.getImageUrls()));
    }

    /**
     * 解析逗号分隔的图片地址。
     * <p>会去除空白项；空串或 null 返回空列表而不是包含空元素的列表。
     *
     * @param images 逗号分隔的图片地址
     * @return 图片地址列表
     */
    private List<String> parseImages(String images) {
        // 空串返回空列表
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        // 去空白并过滤空项
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
