package com.picsou.dto;

import com.picsou.model.RealEstateMetadata;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RealEstateMetadataResponse(
    BigDecimal purchasePrice,
    LocalDate purchaseDate,
    BigDecimal surfaceArea,
    String address,
    String propertyType,
    BigDecimal rentalIncome
) {
    public static RealEstateMetadataResponse from(RealEstateMetadata m) {
        return new RealEstateMetadataResponse(
            m.getPurchasePrice(),
            m.getPurchaseDate(),
            m.getSurfaceArea(),
            m.getAddress(),
            m.getPropertyType(),
            m.getRentalIncome()
        );
    }
}
