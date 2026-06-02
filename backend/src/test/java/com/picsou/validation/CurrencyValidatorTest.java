package com.picsou.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyValidatorTest {

    private final CurrencyValidator validator = new CurrencyValidator();

    @Test
    void acceptsValidIso4217Codes() {
        assertThat(validator.isValid("EUR", null)).isTrue();
        assertThat(validator.isValid("USD", null)).isTrue();
        assertThat(validator.isValid("GBP", null)).isTrue();
    }

    @Test
    void rejectsUnknownCodes() {
        assertThat(validator.isValid("AMAT", null)).isFalse();
        assertThat(validator.isValid("XYZ", null)).isFalse();
        assertThat(validator.isValid("euro", null)).isFalse();
    }

    @Test
    void leavesNullAndBlankToNotBlank() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("", null)).isTrue();
        assertThat(validator.isValid("  ", null)).isTrue();
    }
}
