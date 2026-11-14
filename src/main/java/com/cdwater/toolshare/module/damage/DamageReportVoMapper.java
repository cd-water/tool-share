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

@Mapper(componentModel = "spring")
public interface DamageReportVoMapper {

    @Mapping(target = "toolImageUrl", ignore = true)
    @Mapping(target = "reporterPhone", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "statusName", ignore = true)
    @Mapping(target = "progressStatusName", ignore = true)
    @Mapping(target = "acceptResultName", ignore = true)
    DamageReportVO toVO(DamageReport report);

    @AfterMapping
    default void enrich(DamageReport report, @MappingTarget DamageReportVO vo) {
        vo.setReporterPhone(IdCardCipher.maskPhone(report.getReporterPhone()));
        vo.setImages(parseImages(report.getImages()));
        vo.setStatusName(Dict.label(Dict.REPORT_STATUS, report.getStatus()));
        vo.setProgressStatusName(Dict.label(Dict.PROGRESS_STATUS, report.getProgressStatus()));
        vo.setAcceptResultName(Dict.label(Dict.ACCEPT_RESULT, report.getAcceptResult()));
    }

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
