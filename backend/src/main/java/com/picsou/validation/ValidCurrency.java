package com.picsou.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validates that a string is a valid ISO 4217 currency code (as recognised by
 * {@link java.util.Currency}). Null/blank values pass — pair with {@code @NotBlank}
 * when the field is required. Guards against persisting unknown codes that later
 * crash the frontend's currency formatter (issue #9).
 */
@Documented
@Constraint(validatedBy = CurrencyValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ValidCurrency {
    String message() default "Currency must be a valid ISO 4217 code (e.g., EUR, USD, GBP)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
