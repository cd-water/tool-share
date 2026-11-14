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
 */
@Documented
@Constraint(validatedBy = IdCardValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface IdCard {

    String message() default "请输入正确的 18 位身份证号";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
