package com.ems.concurrency;

import com.ems.domain.model.PolicyAccountBalance;
import com.ems.exception.InsufficientBalanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PolicyAccountBalance entity methods.
 * Validates the balance arithmetic and guard clauses
 * that form the inner defense layer of the concurrency model.
 *
 * These are pure in-memory tests — no database.
 */
class PolicyAccountBalanceConcurrencyTest {

    private static final UUID POLICY_ACCOUNT_ID = UUID.randomUUID();

    // ─────────────────────────────────────────────────────────────
    // reserve()
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reserve() deducts from available and adds to reservedExposure")
    void reserve_updatesBalanceCorrectly() {
        var balance = buildBalance(100_000L, 0L);

        balance.reserve(30_000L);

        assertThat(balance.getReservedExposure()).isEqualTo(30_000L);
        assertThat(balance.getAvailableBalance()).isEqualTo(70_000L);
        assertThat(balance.getConfirmedEaBalance()).isEqualTo(100_000L); // unchanged
    }

    @Test
    @DisplayName("Multiple sequential reserves accumulate correctly")
    void reserve_multipleSequential_accumulatesCorrectly() {
        var balance = buildBalance(100_000L, 0L);

        balance.reserve(20_000L);
        balance.reserve(30_000L);
        balance.reserve(10_000L);

        assertThat(balance.getReservedExposure()).isEqualTo(60_000L);
        assertThat(balance.getAvailableBalance()).isEqualTo(40_000L);
    }

    @Test
    @DisplayName("reserve() at exact available balance succeeds (boundary)")
    void reserve_exactAmount_succeeds() {
        var balance = buildBalance(50_000L, 0L);

        balance.reserve(50_000L);

        assertThat(balance.getAvailableBalance()).isZero();
        assertThat(balance.getReservedExposure()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("reserve() exceeding available balance throws InsufficientBalanceException")
    void reserve_exceedsAvailable_throwsInsufficientBalance() {
        var balance = buildBalance(10_000L, 0L);

        assertThatThrownBy(() -> balance.reserve(10_001L))
            .isInstanceOf(InsufficientBalanceException.class)
            .hasMessageContaining("10000")
            .hasMessageContaining("10001");
    }

    @Test
    @DisplayName("reserve() with existing exposure correctly checks remaining available")
    void reserve_withExistingExposure_checksRemainingAvailable() {
        var balance = buildBalance(100_000L, 60_000L); // 40,000 available

        balance.reserve(40_000L); // should succeed
        assertThat(balance.getAvailableBalance()).isZero();

        // Next reserve should fail
        assertThatThrownBy(() -> balance.reserve(1L))
            .isInstanceOf(InsufficientBalanceException.class);
    }

    // ─────────────────────────────────────────────────────────────
    // release()
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("release() restores available balance and reduces reservation")
    void release_restoresAvailableBalance() {
        var balance = buildBalance(100_000L, 40_000L); // 60,000 available

        balance.release(15_000L);

        assertThat(balance.getReservedExposure()).isEqualTo(25_000L);
        assertThat(balance.getAvailableBalance()).isEqualTo(75_000L);
    }

    @Test
    @DisplayName("release() more than reserved floors at zero (defensive)")
    void release_moreThanReserved_floorsAtZero() {
        var balance = buildBalance(100_000L, 10_000L);

        balance.release(50_000L); // more than reserved

        assertThat(balance.getReservedExposure()).isZero();
        assertThat(balance.getAvailableBalance()).isEqualTo(100_000L);
    }

    // ─────────────────────────────────────────────────────────────
    // settleDebit()
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("settleDebit() reduces confirmedEaBalance and releases reservation")
    void settleDebit_reducesConfirmedAndReleasesReservation() {
        var balance = buildBalance(100_000L, 30_000L);

        // Actual premium matched estimate
        balance.settleDebit(30_000L, 30_000L);

        assertThat(balance.getConfirmedEaBalance()).isEqualTo(70_000L);
        assertThat(balance.getReservedExposure()).isZero();
        assertThat(balance.getAvailableBalance()).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("settleDebit() with actual < reserved returns unused to available")
    void settleDebit_actualLessThanReserved_returnsUnused() {
        var balance = buildBalance(100_000L, 30_000L); // available = 70,000

        // Reserved 30,000 but insurer only charged 20,000
        balance.settleDebit(30_000L, 20_000L);

        assertThat(balance.getConfirmedEaBalance()).isEqualTo(80_000L);
        assertThat(balance.getReservedExposure()).isZero();
        assertThat(balance.getAvailableBalance()).isEqualTo(80_000L);
    }

    // ─────────────────────────────────────────────────────────────
    // settleCredit()
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("settleCredit() increases confirmed balance (DELETE endorsement)")
    void settleCredit_increasesConfirmedBalance() {
        var balance = buildBalance(80_000L, 10_000L);

        balance.settleCredit(20_000L);

        assertThat(balance.getConfirmedEaBalance()).isEqualTo(100_000L);
        assertThat(balance.getAvailableBalance()).isEqualTo(90_000L); // 100k - 10k reserved
    }

    // ─────────────────────────────────────────────────────────────
    // Balance equation invariant
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Balance equation holds after mixed operations: available = confirmed - reserved")
    void balanceEquation_holdsAfterMixedOperations() {
        var balance = buildBalance(200_000L, 0L);

        balance.reserve(50_000L);          // reserved=50k, available=150k
        balance.reserve(30_000L);          // reserved=80k, available=120k
        balance.settleDebit(50_000L, 45_000L); // confirmed=155k, reserved=30k, available=125k
        balance.settleCredit(10_000L);     // confirmed=165k, reserved=30k, available=135k
        balance.release(10_000L);          // reserved=20k, available=145k

        assertBalanceEquation(balance);
        assertThat(balance.getConfirmedEaBalance()).isEqualTo(165_000L);
        assertThat(balance.getReservedExposure()).isEqualTo(20_000L);
        assertThat(balance.getAvailableBalance()).isEqualTo(145_000L);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private PolicyAccountBalance buildBalance(long confirmed, long reserved) {
        return PolicyAccountBalance.builder()
            .policyAccountId(POLICY_ACCOUNT_ID)
            .confirmedEaBalance(confirmed)
            .reservedExposure(reserved)
            .availableBalance(confirmed - reserved)
            .build();
    }

    private void assertBalanceEquation(PolicyAccountBalance balance) {
        assertThat(balance.getAvailableBalance())
            .as("Balance equation: available = confirmed - reserved")
            .isEqualTo(balance.getConfirmedEaBalance() - balance.getReservedExposure());
    }
}
