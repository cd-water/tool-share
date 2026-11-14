package com.cdwater.toolshare.module.message;

import com.cdwater.toolshare.shared.web.Dict;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface MessageVoMapper {

    @Mapping(target = "typeName", ignore = true)
    @Mapping(target = "readStatus", ignore = true)
    MessageVO toVO(Message message);

    @AfterMapping
    default void enrich(Message message, @MappingTarget MessageVO vo) {
        vo.setTypeName(Dict.label(Dict.MESSAGE_TYPE, message.getType()));
        vo.setReadStatus(message.getReadStatus() != null && message.getReadStatus() == 1);
    }
}
