package com.cdwater.toolshare.module.auth;

import org.mapstruct.Mapper;

/**
 * 管理端账号实体到视图对象的 MapStruct 映射。
 * <p>按同名字段映射，天然不会带出 password。
 */
@Mapper(componentModel = "spring")
public interface AdminVoMapper {

    /**
     * 将账号实体转换为视图对象。
     *
     * @param admin 账号实体，可为 null
     * @return 视图对象；入参为 null 时返回 null
     */
    AdminVO toVO(Admin admin);
}
