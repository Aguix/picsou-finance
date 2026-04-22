package com.picsou.service;

import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ManualTransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingComputeService holdingComputeService;
    private final FinaryPersistenceHelper finaryPersistenceHelper;

    private static final Set<AccountType> INVESTMENT_TYPES =
        Set.of(AccountType.PEA, AccountType.COMPTE_TITRES, AccountType.CRYPTO);

    @Transactional
    public TransactionResponse addTransaction(Long accountId, Long memberId, TransactionRequest req) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        Transaction tx = Transaction.builder()
            .account(account)
            .date(req.date())
            .description(req.description())
            .amount(req.amount())
            .txType(req.txType())
            .ticker(req.ticker())
            .quantity(req.quantity())
            .pricePerUnit(req.pricePerUnit())
            .isManual(true)
            .nativeCurrency(req.currency() != null ? req.currency() : "EUR")
            .build();

        transactionRepository.save(tx);

        if (INVESTMENT_TYPES.contains(account.getType())) {
            holdingComputeService.recomputeHoldings(account);
        } else {
            recomputeCashBalance(account);
            finaryPersistenceHelper.reconstructSnapshotsFromDb(account);
        }

        return TransactionResponse.from(tx);
    }

    @Transactional
    public void deleteTransaction(Long accountId, Long txId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        Transaction tx = transactionRepository.findById(txId)
            .orElseThrow(() -> ResourceNotFoundException.transaction(txId));

        if (!tx.getAccount().getId().equals(account.getId())) {
            throw ResourceNotFoundException.transaction(txId);
        }

        if (!tx.isManual()) {
            throw new IllegalArgumentException("Cannot delete a synced transaction");
        }

        transactionRepository.delete(tx);

        if (INVESTMENT_TYPES.contains(account.getType())) {
            holdingComputeService.recomputeHoldings(account);
        } else {
            recomputeCashBalance(account);
            finaryPersistenceHelper.reconstructSnapshotsFromDb(account);
        }
    }

    private void recomputeCashBalance(Account account) {
        BigDecimal sum = transactionRepository.findByAccountIdOrderByDateDesc(account.getId())
            .stream()
            .map(Transaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        account.setCurrentBalance(sum);
        accountRepository.save(account);
    }
}
