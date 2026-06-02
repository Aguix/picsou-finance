package com.picsou.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RealEstateMetadataRequest(
    @NotNull @DecimalMin("0") BigDecimal purchasePrice,
    LocalDate purchaseDate,
    BigDecimal surfaceArea,
    @Size(max = 500) String address,
    @Size(max = 50) String propertyType,
    @DecimalMin("0") BigDecimal rentalIncome
) {}
