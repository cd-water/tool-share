/**
 * 用户实体到视图的 MapStruct 映射文件。
 * <p>显式忽略 phone 与 idCard 的自动映射，改由 mask 后置处理统一脱敏：
 * 手机号中间四位替换为星号，身份证通过 IdCardCipher 解密后脱敏。
 * <h2>为什么用 @Context</h2>
 * <p>MapStruct 映射器由 Spring 管理，但脱敏器需要按调用传入，
 * 使用 @Context 把 IdCardCipher 带进映射过程，避免把加密组件做成静态依赖。
 * <p>调用方：UserService。
 */
package com.cdwater.toolshare.module.auth;

import com.cdwater.toolshare.shared.security.IdCardCipher;
import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * 用户实体到视图的 MapStruct 映射。
 * <p>phone 与 idCard 显式忽略自动映射，改由 {@code mask} 后置处理统一脱敏；
 * 身份证解密/脱敏逻辑集中在 {@link IdCardCipher}，映射器不直接接触明文。
 */
@Mapper(componentModel = "spring")
public interface UserVoMapper {

    @Mapping(target = "phone", ignore = true)
    @Mapping(target = "idCard", ignore = true)
    /**
     * 转换用户实体，并通过上下文传入脱敏器。
     *
     * @param user         用户实体
     * @param idCardCipher 身份证脱敏器
     * @return 脱敏后的用户视图
     */
    UserVO toVO(User user, @Context IdCardCipher idCardCipher);

    // 后置映射：对手机号与身份证做统一脱敏，覆盖自动映射结果
    @AfterMapping
    default void mask(User user, @MappingTarget UserVO vo, @Context IdCardCipher idCardCipher) {
        vo.setPhone(IdCardCipher.maskPhone(user.getPhone()));
        vo.setIdCard(idCardCipher.mask(user.getIdCard()));
    }
}
