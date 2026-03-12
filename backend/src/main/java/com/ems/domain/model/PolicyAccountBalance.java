package com.ems.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "policy_account_balance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyAccountBalance {

    @Id
    @Column(name = "policy_account_id", updatable = false)
    private UUID policyAccountId;

    /** Optimistic lock — secondary guard behind the pessimistic write lock. */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /** Confirmed balance from insurer (source of truth). Stored in smallest currency unit. */
    @Column(name = "confirmed_ea_balance", nullable = false)
    @Builder.Default
    private Long confirmedEaBalance = 0L;

    /** Sum of premiums for all non-terminal ADD endorsements. */
    @Column(name = "reserved_exposure", nullable = false)
    @Builder.Default
    private Long reservedExposure = 0L;

    /** confirmedEaBalance - reservedExposure. Maintained by LedgerService. */
    @Column(name = "available_balance", nullable = false)
    @Builder.Default
    private Long availableBalance = 0L;

    @Column(name = "pending_credit")
    @Builder.Default
    private Long pendingCredit = 0L;

    @Column(name = "pending_debit")
    @Builder.Default
    private Long pendingDebit = 0L;

    @Column(name = "last_insurer_balance_sync_at")
    private OffsetDateTime lastInsurerBalanceSyncAt;

    @Column(name = "last_reconciled_at")
    private OffsetDateTime lastReconciledAt;

    @Column(name = "drift_amount", nullable = false)
    @Builder.Default
    private Long driftAmount = 0L;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    /**
     * Reserves exposure for an ADD endorsement.
     * Throws IllegalStateException if available balance is insufficient.
     */
    public void reserve(long amount) {
        if (availableBalance < amount) {
            throw new com.ems.exception.InsufficientBalanceException(
                policyAccountId, availableBalance, amount
            );
        }
        this.reservedExposure += amount;
        this.availableBalance = this.confirmedEaBalance - this.reservedExposure;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Releases a previously reserved amount (e.g., on cancellation or terminal failure).
     */
    public void release(long amount) {
        this.reservedExposure = Math.max(0, this.reservedExposure - amount);
        this.availableBalance = this.confirmedEaBalance - this.reservedExposure;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Settles a debit — moves confirmed balance. Called on EXECUTED for ADD type.
     */
    public void settleDebit(long reservedAmount, long actualAmount) {
        this.reservedExposure = Math.max(0, this.reservedExposure - reservedAmount);
        this.confirmedEaBalance -= actualAmount;
        this.availableBalance = this.confirmedEaBalance - this.reservedExposure;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Settles a credit — called on EXECUTED for DELETE type.
     */
    public void settleCredit(long amount) {
        this.confirmedEaBalance += amount;
        this.availableBalance = this.confirmedEaBalance - this.reservedExposure;
        this.updatedAt = OffsetDateTime.now();
    }
}
