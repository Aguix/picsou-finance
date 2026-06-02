package com.picsou.service.budget;

import com.picsou.dto.RecurringOccurrenceResponse;
import com.picsou.dto.RecurringSeriesRequest;
import com.picsou.dto.RecurringSeriesResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Category;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RecurringSeriesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the lifecycle of {@link RecurringSeries} (list, confirm, ignore, manual add/edit,
 * delete) and projects confirmed series onto a forward-looking calendar of due dates.
 * Detection itself lives in {@link RecurringDetectionService}; this service is the user-facing
 * surface over the resulting rows.
 */
@Service
@Transactional(readOnly = true)
public class RecurringSeriesService {

    private final RecurringSeriesRepository seriesRepository;
    private final CategoryRepository categoryRepository;
    private final FamilyMemberRepository familyMemberRepository;

    public RecurringSeriesService(
        RecurringSeriesRepository seriesRepository,
        CategoryRepository categoryRepository,
        FamilyMemberRepository familyMemberRepository
    ) {
        this.seriesRepository = seriesRepository;
        this.categoryRepository = categoryRepository;
        this.familyMemberRepository = familyMemberRepository;
    }

    /** All series for the member, optionally filtered by status, soonest due first. */
    public List<RecurringSeriesResponse> findAll(Long memberId, RecurringStatus status) {
        List<RecurringSeries> series = status == null
            ? seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(memberId)
            : seriesRepository.findAllByMemberIdAndStatusOrderByNextDueDateAsc(memberId, status);
        return series.stream().map(RecurringSeriesResponse::from).toList();
    }

    @Transactional
    public RecurringSeriesResponse confirm(Long id, Long memberId) {
        return setStatus(id, memberId, RecurringStatus.CONFIRMED);
    }

    /**
     * Dismiss a suggestion. The series is kept (not deleted) with status IGNORED so detection
     * does not resurrect it on the next sync — see {@link RecurringDetectionService}.
     */
    @Transactional
    public RecurringSeriesResponse ignore(Long id, Long memberId) {
        return setStatus(id, memberId, RecurringStatus.IGNORED);
    }

    private RecurringSeriesResponse setStatus(Long id, Long memberId, RecurringStatus status) {
        RecurringSeries series = require(id, memberId);
        series.setStatus(status);
        return RecurringSeriesResponse.from(seriesRepository.save(series));
    }

    /** Manually declare a series the detector missed; user-declared ones start CONFIRMED. */
    @Transactional
    public RecurringSeriesResponse create(RecurringSeriesRequest req, Long memberId) {
        RecurringSeries series = RecurringSeries.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .label(req.label().trim())
            .counterparty(req.counterparty() != null ? req.counterparty().trim() : null)
            .expectedAmount(req.expectedAmount())
            .cadence(req.cadence())
            .nextDueDate(req.nextDueDate())
            .category(resolveCategory(req.categoryId(), memberId))
            .status(RecurringStatus.CONFIRMED)
            .build();
        return RecurringSeriesResponse.from(seriesRepository.save(series));
    }

    @Transactional
    public RecurringSeriesResponse update(Long id, RecurringSeriesRequest req, Long memberId) {
        RecurringSeries series = require(id, memberId);
        series.setLabel(req.label().trim());
        series.setCounterparty(req.counterparty() != null ? req.counterparty().trim() : null);
        series.setExpectedAmount(req.expectedAmount());
        series.setCadence(req.cadence());
        series.setNextDueDate(req.nextDueDate());
        series.setCategory(resolveCategory(req.categoryId(), memberId));
        return RecurringSeriesResponse.from(seriesRepository.save(series));
    }

    @Transactional
    public void delete(Long id, Long memberId) {
        seriesRepository.delete(require(id, memberId));
    }

    /**
     * Project confirmed series onto a calendar from today through {@code today + horizonDays}.
     * Each series is rolled forward by its cadence from {@code nextDueDate}, emitting one
     * occurrence per period that lands inside the window.
     */
    public List<RecurringOccurrenceResponse> upcoming(Long memberId, LocalDate today, int horizonDays) {
        LocalDate end = today.plusDays(horizonDays);
        List<RecurringOccurrenceResponse> occurrences = new ArrayList<>();

        for (RecurringSeries series : seriesRepository
            .findAllByMemberIdAndStatusOrderByNextDueDateAsc(memberId, RecurringStatus.CONFIRMED)) {

            LocalDate due = series.getNextDueDate();
            if (due == null) {
                continue;
            }
            // Skip past-due dates forward into the window without emitting stale occurrences.
            while (due.isBefore(today)) {
                due = series.getCadence().next(due);
            }
            // Emit every projected charge up to the horizon (cap iterations defensively).
            for (int guard = 0; !due.isAfter(end) && guard < 400; guard++) {
                occurrences.add(toOccurrence(series, due));
                due = series.getCadence().next(due);
            }
        }
        occurrences.sort(java.util.Comparator.comparing(RecurringOccurrenceResponse::dueDate));
        return occurrences;
    }

    private RecurringOccurrenceResponse toOccurrence(RecurringSeries s, LocalDate due) {
        Category category = s.getCategory();
        return new RecurringOccurrenceResponse(
            s.getId(),
            s.getLabel(),
            s.getCounterparty(),
            s.getExpectedAmount(),
            due,
            category != null ? category.getId() : null,
            category != null ? category.getName() : null,
            category != null ? category.getColor() : null,
            category != null ? category.getIcon() : null
        );
    }

    private RecurringSeries require(Long id, Long memberId) {
        return seriesRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.recurringSeries(id));
    }

    private Category resolveCategory(Long categoryId, Long memberId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findByIdAndMemberId(categoryId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.category(categoryId));
    }
}
