package com.picsou.service.budget;

import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import com.picsou.model.Transaction;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RecurringSeriesRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringDetectionServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock RecurringSeriesRepository seriesRepository;
    @Mock FamilyMemberRepository familyMemberRepository;

    @InjectMocks RecurringDetectionService service;

    private static final Long MEMBER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private static Transaction tx(LocalDate date, String amount, String counterparty) {
        return Transaction.builder()
            .id(date.toEpochDay()) // stable, unique-enough id for ordering
            .date(date)
            .amount(new BigDecimal(amount))
            .counterparty(counterparty)
            .description(counterparty)
            .build();
    }

    // ─── Pure analysis ────────────────────────────────────────────────────────

    @Test
    void analyse_detectsMonthlySubscription() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-12.99", "Netflix")
        );

        RecurringDetectionService.Candidate c = RecurringDetectionService.analyse(txs);

        assertThat(c).isNotNull();
        assertThat(c.cadence()).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(c.expectedAmount()).isEqualByComparingTo("-12.99");
        assertThat(c.lastSeen()).isEqualTo(LocalDate.of(2026, 5, 6));
        assertThat(c.nextDue()).isEqualTo(LocalDate.of(2026, 6, 6));
    }

    @Test
    void analyse_toleratesSmallAmountDrift() {
        // A modest price bump within 15% of the median still counts as the same series.
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 1), "-10.00", "Spotify"),
            tx(LocalDate.of(2026, 4, 1), "-10.00", "Spotify"),
            tx(LocalDate.of(2026, 5, 1), "-11.00", "Spotify")
        );

        assertThat(RecurringDetectionService.analyse(txs)).isNotNull();
    }

    @Test
    void analyse_rejectsTooFewOccurrences() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 4, 1), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 1), "-12.99", "Netflix")
        );

        assertThat(RecurringDetectionService.analyse(txs)).isNull();
    }

    @Test
    void analyse_rejectsIrregularIntervals() {
        // 30d then 90d gap — not a stable cadence.
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 1, 1), "-20.00", "Irregular"),
            tx(LocalDate.of(2026, 1, 31), "-20.00", "Irregular"),
            tx(LocalDate.of(2026, 5, 1), "-20.00", "Irregular")
        );

        assertThat(RecurringDetectionService.analyse(txs)).isNull();
    }

    @Test
    void analyse_rejectsUnstableAmounts() {
        // Same monthly rhythm but wildly different amounts — looks like groceries, not a sub.
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 3), "-15.00", "Carrefour"),
            tx(LocalDate.of(2026, 4, 3), "-80.00", "Carrefour"),
            tx(LocalDate.of(2026, 5, 3), "-42.00", "Carrefour")
        );

        assertThat(RecurringDetectionService.analyse(txs)).isNull();
    }

    @Test
    void normalise_collapsesCaseAndWhitespace() {
        assertThat(RecurringDetectionService.normalise("  NETFLIX   EU "))
            .isEqualTo("netflix eu");
    }

    // ─── Full detect() with persistence ─────────────────────────────────────────

    @Test
    void detect_createsSuggestedSeriesForNewCounterparty() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-12.99", "Netflix")
        );
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(txs);
        when(seriesRepository.findByMemberIdAndCounterpartyIgnoreCase(MEMBER_ID, "Netflix"))
            .thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(anyLong())).thenReturn(null);

        int upserted = service.detect(MEMBER_ID, TODAY);

        assertThat(upserted).isEqualTo(1);
        ArgumentCaptor<RecurringSeries> saved = ArgumentCaptor.forClass(RecurringSeries.class);
        verify(seriesRepository).save(saved.capture());
        RecurringSeries series = saved.getValue();
        assertThat(series.getStatus()).isEqualTo(RecurringStatus.SUGGESTED);
        assertThat(series.getCadence()).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(series.getLabel()).isEqualTo("Netflix");
        assertThat(series.getNextDueDate()).isEqualTo(LocalDate.of(2026, 6, 6));
    }

    @Test
    void detect_doesNotResurrectIgnoredSeries() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-12.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-12.99", "Netflix")
        );
        RecurringSeries ignored = RecurringSeries.builder()
            .id(1L).counterparty("Netflix").status(RecurringStatus.IGNORED).build();
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(txs);
        when(seriesRepository.findByMemberIdAndCounterpartyIgnoreCase(MEMBER_ID, "Netflix"))
            .thenReturn(Optional.of(ignored));

        int upserted = service.detect(MEMBER_ID, TODAY);

        assertThat(upserted).isZero();
        verify(seriesRepository, never()).save(any());
    }

    @Test
    void detect_refreshesConfirmedSeriesInPlace() {
        List<Transaction> txs = List.of(
            tx(LocalDate.of(2026, 3, 5), "-13.99", "Netflix"),
            tx(LocalDate.of(2026, 4, 4), "-13.99", "Netflix"),
            tx(LocalDate.of(2026, 5, 6), "-13.99", "Netflix")
        );
        RecurringSeries confirmed = RecurringSeries.builder()
            .id(1L).counterparty("Netflix").label("Netflix")
            .expectedAmount(new BigDecimal("-12.99"))
            .cadence(RecurringCadence.MONTHLY)
            .status(RecurringStatus.CONFIRMED).build();
        when(transactionRepository.findByMemberIdAndDateBetween(eq(MEMBER_ID), any(), any()))
            .thenReturn(txs);
        when(seriesRepository.findByMemberIdAndCounterpartyIgnoreCase(MEMBER_ID, "Netflix"))
            .thenReturn(Optional.of(confirmed));

        int upserted = service.detect(MEMBER_ID, TODAY);

        assertThat(upserted).isEqualTo(1);
        assertThat(confirmed.getStatus()).isEqualTo(RecurringStatus.CONFIRMED); // unchanged
        assertThat(confirmed.getExpectedAmount()).isEqualByComparingTo("-13.99"); // refreshed
        verify(seriesRepository).save(confirmed);
    }
}
