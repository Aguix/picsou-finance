package com.picsou.dto;

import java.util.List;

public record FinaryImportResultResponse(
    int accountsCreated,
    int accountsMapped,
    int accountsSkipped,
    int snapshotsCreated,
    int transactionsImported,
    List<ImportedAccountSummary> importedAccounts
) {}
