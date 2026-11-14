package com.cdwater.toolshare.module.auth;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AdminVoMapper {

    AdminVO toVO(Admin admin);
}
