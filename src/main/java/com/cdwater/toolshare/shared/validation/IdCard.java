package com.cdwater.toolshare.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 18 位身份证号校验（含校验位）。空值视为合法，便于「留空表示不修改」。
 *
 * <p>只校验格式与校验位，不校验行政区划是否真实存在、出生日期是否
 * 合理。业务上如需更强校验，应在服务层补充。</p>
 *
 * <p>可用于字段与方法参数。注解本身不改变存储方式；身份证的加密与
 * 脱敏由 {@code IdCardCipher} 负责。</p>
 *
 * <p>空值放行是有意设计：更新资料时用户可能不修改身份证，此时请求体
 * 中该字段为 null，不应因此拒绝整个请求。</p>
 *
 * @see IdCardValidator
 */
@Documented
@Constraint(validatedBy = IdCardValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface IdCard {

    /** 校验失败时的默认提示。 */
    String message() default "请输入正确的 18 位身份证号";

    /** 校验分组，遵循 Bean Validation 规范。 */
    Class<?>[] groups() default {};

    /** 负载信息，遵循 Bean Validation 规范。 */
    Class<? extends Payload>[] payload() default {};
}
