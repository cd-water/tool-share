package com.cdwater.toolshare.module.damage;

import com.cdwater.toolshare.shared.security.IdCardCipher;
import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 报修单实体到视图对象的 MapStruct 映射器。
 * <p>基础字段由 MapStruct 自动映射；工具主图、电话脱敏、图片拆分以及
 * <p>新增实体字段时若不在目标对象中，需要显式 ignore，避免 MapStruct
 * 在编译期报未映射属性；派生字段统一放在 after-mapping 阶段处理，
 * <p>派生字段的处理原则：只读、无副作用、不访问数据库。工具主图需要
 * 查询工具表，因此放在 Service 层而不是映射器；电话脱敏与字典翻译
 * 属于纯计算，放在 after-mapping 阶段最合适。
 * <p>图片解析会过滤空白项，兼容历史数据中多余逗号或空格的情况。
 * 保证实体本身不被修改。
 * 三个状态名称字段在 {@link #enrich(DamageReport, DamageReportVO)} 中补齐。
 */
@Mapper(componentModel = "spring")
public interface DamageReportVoMapper {

    /**
     * 将实体映射为视图对象。
     * <p>工具主图需要额外查询工具表，电话需要脱敏，图片需要拆分，
     * <p>工具主图由 Service 层按需补齐：列表页批量查询，详情页单条查询，
     * 映射器本身不访问数据库，便于单元测试复用。
     * 状态名称需要查字典，因此这些字段在 after-mapping 阶段处理。
     *
     * @param report 报修单实体
     * <p>调用方必须在映射后执行 after-mapping 或手动补齐扩展字段。
     * @return 未补齐扩展字段的视图对象
     */
    @Mapping(target = "toolImageUrl", ignore = true)
    @Mapping(target = "reporterPhone", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "statusName", ignore = true)
    @Mapping(target = "progressStatusName", ignore = true)
    @Mapping(target = "acceptResultName", ignore = true)
    DamageReportVO toVO(DamageReport report);

    /**
     * 映射后补齐派生字段。
     * <p>电话统一脱敏，避免居民隐私在管理端与居民端之间泄露；
     * 状态编码通过共享字典翻译为中文名称。
     *
     * @param report 源实体
     * <p>电话脱敏使用共享的 {@code IdCardCipher.maskPhone}，与登录、租赁等
     * 其他模块保持同一脱敏规则。
     * <p>映射器不修改源实体，所有派生值只写入目标对象。
     * @param vo     待补齐的目标视图对象
     */
    @AfterMapping
    default void enrich(DamageReport report, @MappingTarget DamageReportVO vo) {
        vo.setReporterPhone(IdCardCipher.maskPhone(report.getReporterPhone()));
        vo.setImages(parseImages(report.getImages()));
        vo.setStatusName(Dict.label(Dict.REPORT_STATUS, report.getStatus()));
        vo.setProgressStatusName(Dict.label(Dict.PROGRESS_STATUS, report.getProgressStatus()));
        vo.setAcceptResultName(Dict.label(Dict.ACCEPT_RESULT, report.getAcceptResult()));
    }

    /**
     * 把逗号分隔的图片地址拆成数组，自动去除空白与空项。
     *
     * @param images 数据库中的逗号分隔字符串，可为空
     * <p>拆分后过滤空白项，防止历史脏数据产生空字符串图片地址。
     * @return 非空的图片地址列表；无内容时返回空列表
     */
    private List<String> parseImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
