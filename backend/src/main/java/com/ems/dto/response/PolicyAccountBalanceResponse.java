package com.ems.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class PolicyAccountBalanceResponse {

    @JsonProperty("policy_account_id")
    private UUID policyAccountId;

    @JsonProperty("confirmed_ea_balance")
    private Long confirmedEaBalance;

    @JsonProperty("reserved_exposure")
    private Long reservedExposure;

    @JsonProperty("available_balance")
    private Long availableBalance;

    @JsonProperty("pending_credit")
    private Long pendingCredit;

    @JsonProperty("pending_debit")
    private Long pendingDebit;

    @JsonProperty("last_insurer_balance_sync_at")
    private OffsetDateTime lastInsurerBalanceSyncAt;

    @JsonProperty("last_reconciled_at")
    private OffsetDateTime lastReconciledAt;

    @JsonProperty("drift_amount")
    private Long driftAmount;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;
}
