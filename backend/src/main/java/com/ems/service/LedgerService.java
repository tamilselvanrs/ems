package com.ems.service;

import com.ems.domain.enums.LedgerEntryType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

@Service
@Slf4j
public class LedgerService {

    public void writeEntry(
            UUID policyAccountId,
            UUID endorsementRequestId,
            LedgerEntryType type,
            long amount,
            String currencyCode,
            LocalDate effectiveDate) {
        log.debug("Ledger entry: policyAccount={}, endorsement={}, type={}, amount={} {}",
            policyAccountId, endorsementRequestId, type, amount, currencyCode);
    }
}
