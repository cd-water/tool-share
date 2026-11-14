package com.cdwater.toolshare.module.auth;

import com.cdwater.toolshare.shared.security.IdCardCipher;
import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface UserVoMapper {

    @Mapping(target = "phone", ignore = true)
    @Mapping(target = "idCard", ignore = true)
    UserVO toVO(User user, @Context IdCardCipher idCardCipher);

    @AfterMapping
    default void mask(User user, @MappingTarget UserVO vo, @Context IdCardCipher idCardCipher) {
        vo.setPhone(IdCardCipher.maskPhone(user.getPhone()));
        vo.setIdCard(idCardCipher.mask(user.getIdCard()));
    }
}
