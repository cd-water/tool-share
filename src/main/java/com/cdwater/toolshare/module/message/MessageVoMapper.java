package com.cdwater.toolshare.module.message;

import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * 消息实体到视图对象的 MapStruct 映射器。
 * <p>类型中文名与布尔已读状态无法直接同名映射，因此在 after-mapping
 * 阶段补齐；其余字段由 MapStruct 自动映射。
 */
@Mapper(componentModel = "spring")
public interface MessageVoMapper {

    /**
     * 将消息实体映射为视图对象。
     * <p>typeName 与 readStatus 需要派生计算，先忽略，映射后统一补齐。
     *
     * @param message 消息实体
     * @return 未补齐派生字段的视图对象
     */
    @Mapping(target = "typeName", ignore = true)
    @Mapping(target = "readStatus", ignore = true)
    MessageVO toVO(Message message);

    /**
     * 映射后补齐消息类型中文名与布尔已读状态。
     *
     * @param message 源实体
     * @param vo      目标视图对象
     */
    @AfterMapping
    default void enrich(Message message, @MappingTarget MessageVO vo) {
        // 字典中不存在的类型会回退为原编码，避免前端显示空白。
        vo.setTypeName(Dict.label(Dict.MESSAGE_TYPE, message.getType()));
        // 数据库用 0/1 存储，对外统一转为布尔值。
        vo.setReadStatus(message.getReadStatus() != null && message.getReadStatus() == 1);
    }
}
