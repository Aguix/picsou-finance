package com.picsou.dto;

import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A recurring series as surfaced to the UI: its expected amount/cadence, lifecycle status,
 * the linked category (if any) and its next projected due date for the calendar.
 */
public record RecurringSeriesResponse(
    Long id,
    String label,
    String counterparty,
    BigDecimal expectedAmount,
    RecurringCadence cadence,
    RecurringStatus status,
    LocalDate nextDueDate,
    LocalDate lastSeenDate,
    Long categoryId,
    String categoryName,
    String categoryColor,
    String categoryIcon
) {
    public static RecurringSeriesResponse from(RecurringSeries s) {
        var category = s.getCategory();
        return new RecurringSeriesResponse(
            s.getId(),
            s.getLabel(),
            s.getCounterparty(),
            s.getExpectedAmount(),
            s.getCadence(),
            s.getStatus(),
            s.getNextDueDate(),
            s.getLastSeenDate(),
            category != null ? category.getId() : null,
            category != null ? category.getName() : null,
            category != null ? category.getColor() : null,
            category != null ? category.getIcon() : null
        );
    }
}
