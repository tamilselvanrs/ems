package com.ems.concurrency;

import com.ems.domain.enums.EndorsementStatus;
import com.ems.domain.enums.ExecutionMode;
import com.ems.domain.enums.RequestType;
import com.ems.domain.model.*;
import com.ems.dto.request.AddEndorsementRequest;
import com.ems.dto.response.EndorsementResponse;
import com.ems.exception.InsufficientBalanceException;
import com.ems.repository.*;
import com.ems.service.EndorsementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies concurrent endorsement submissions
 * are properly serialized via pessimistic locking on PolicyAccountBalance.
 *
 * Runs against H2 in PostgreSQL-compatibility mode with ddl-auto=create-drop.
 * Each test gets a fresh database via @BeforeEach cleanup.
 */
@SpringBootTest
@ActiveProfiles("test")
class EndorsementConcurrencyIT {

    @Autowired private EndorsementService endorsementService;
    @Autowired private PolicyAccountRepository policyAccountRepository;
    @Autowired private PolicyAccountBalanceRepository balanceRepository;
    @Autowired private InsurerConfigRepository insurerConfigRepository;
    @Autowired private PolicyPricingRuleRepository pricingRuleRepository;
    @Autowired private EndorsementRequestRepository endorsementRequestRepository;

    private UUID policyAccountId;
    private UUID insurerId;

    @BeforeEach
    void setUp() {
        // Clean slate
        endorsementRequestRepository.deleteAll();
        balanceRepository.deleteAll();
        pricingRuleRepository.deleteAll();
        policyAccountRepository.deleteAll();
        insurerConfigRepository.deleteAll();

        policyAccountId = UUID.randomUUID();
        insurerId = UUID.randomUUID();

        // Seed insurer config
        insurerConfigRepository.save(InsurerConfig.builder()
            .insurerConfigId(UUID.randomUUID())
            .insurerId(insurerId)
            .insurerName("Test Insurer")
            .executionMode(ExecutionMode.REALTIME)
            .backdateWindowDays(30)
            .build());

        // Seed policy account
        policyAccountRepository.save(PolicyAccount.builder()
            .policyAccountId(policyAccountId)
            .insurerId(insurerId)
            .employerId(UUID.randomUUID())
            .policyNumber("POL-TEST-001")
            .currencyCode("INR")
            .policyStartDate(LocalDate.now().minusYears(1))
            .policyEndDate(LocalDate.now().plusYears(1))
            .build());

        // Seed pricing rule: 5000 paisa per ADD for SELF, male, age 18-65
        pricingRuleRepository.save(PolicyPricingRule.builder()
            .policyAccountId(policyAccountId)
            .requestType(RequestType.ADD)
            .memberType("SELF")
            .ageBandMin(18)
            .ageBandMax(65)
            .gender("MALE")
            .basePremium(5_000L) // 50 INR in paisa
            .build());
    }

    // ─────────────────────────────────────────────────────────────
    // Test 1: Concurrent reservations must serialize — no overdraft
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 concurrent endorsements against a balance that can only hold 5 → exactly 5 succeed, 5 fail")
    void concurrentReservations_exactlyFitCount_succeed() throws Exception {
        // Balance = 25,000 paisa, premium = 5,000 per endorsement → max 5 can succeed
        balanceRepository.save(PolicyAccountBalance.builder()
            .policyAccountId(policyAccountId)
            .confirmedEaBalance(25_000L)
            .reservedExposure(0L)
            .availableBalance(25_000L)
            .build());

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);  // all threads start simultaneously
        CountDownLatch finishGate = new CountDownLatch(threadCount);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger insufficientBalanceFailures = new AtomicInteger(0);
        AtomicInteger otherFailures = new AtomicInteger(0);
        List<EndorsementResponse> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startGate.await(); // wait for all threads to be ready
                    AddEndorsementRequest request = buildRequest("concurrent-" + idx);
                    EndorsementResponse response = endorsementService.addEndorsement(policyAccountId, request);
                    responses.add(response);
                    successes.incrementAndGet();
                } catch (InsufficientBalanceException e) {
                    insufficientBalanceFailures.incrementAndGet();
                } catch (Exception e) {
                    otherFailures.incrementAndGet();
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown(); // fire!
        boolean finished = finishGate.await(30, TimeUnit.SECONDS);

        executor.shutdown();
        assertThat(finished).as("All threads should finish within timeout").isTrue();

        // Exactly 5 should succeed, 5 should fail with insufficient balance
        assertThat(successes.get()).as("Successful endorsements").isEqualTo(5);
        assertThat(insufficientBalanceFailures.get()).as("InsufficientBalance rejections").isEqualTo(5);
        assertThat(otherFailures.get()).as("Unexpected failures").isZero();

        // Verify final balance state: all 25,000 reserved, 0 available
        PolicyAccountBalance finalBalance = balanceRepository.findById(policyAccountId).orElseThrow();
        assertThat(finalBalance.getConfirmedEaBalance()).isEqualTo(25_000L);
        assertThat(finalBalance.getReservedExposure()).isEqualTo(25_000L);
        assertThat(finalBalance.getAvailableBalance()).isZero();

        // Verify exactly 5 endorsement records in DB
        var endorsements = endorsementRequestRepository.findByPolicyAccountId(policyAccountId);
        assertThat(endorsements).hasSize(5);
        assertThat(endorsements).allMatch(e -> e.getCurrentStatus() == EndorsementStatus.VALIDATED);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 2: Concurrent duplicate idempotency keys — exactly one created
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5 concurrent submissions with the same idempotency key → exactly 1 endorsement persisted")
    void concurrentDuplicateIdempotencyKey_onlyOneCreated() throws Exception {
        balanceRepository.save(PolicyAccountBalance.builder()
            .policyAccountId(policyAccountId)
            .confirmedEaBalance(100_000L)
            .reservedExposure(0L)
            .availableBalance(100_000L)
            .build());

        int threadCount = 5;
        String sharedIdempotencyKey = "IDEM-SHARED-" + UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(threadCount);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger constraintViolations = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    AddEndorsementRequest request = buildRequest(sharedIdempotencyKey);
                    endorsementService.addEndorsement(policyAccountId, request);
                    successes.incrementAndGet();
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // Expected: unique constraint on (policy_account_id, idempotency_key)
                    // rejects concurrent duplicates — caller should retry and hit idempotency check
                    constraintViolations.incrementAndGet();
                } catch (Exception ignored) {
                    // Other transient errors under contention (e.g. lock timeouts)
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = finishGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("All threads should finish within timeout").isTrue();

        // Exactly 1 insert wins; the rest get constraint violations
        assertThat(successes.get())
            .as("Exactly one thread should succeed creating the endorsement")
            .isEqualTo(1);
        assertThat(constraintViolations.get())
            .as("Remaining threads should hit the unique constraint")
            .isEqualTo(threadCount - 1);

        // Only 1 endorsement in DB — the unique constraint guarantees this
        var endorsements = endorsementRequestRepository.findByPolicyAccountId(policyAccountId);
        assertThat(endorsements)
            .as("Unique constraint ensures exactly one endorsement persisted")
            .hasSize(1);

        // Only 5,000 reserved (one endorsement)
        PolicyAccountBalance finalBalance = balanceRepository.findById(policyAccountId).orElseThrow();
        assertThat(finalBalance.getReservedExposure()).isEqualTo(5_000L);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 3: Balance never goes negative under contention
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("20 concurrent endorsements against tight balance → balance never goes negative")
    void concurrentReservations_balanceNeverGoesNegative() throws Exception {
        // Balance = 15,000, premium = 5,000 → max 3 endorsements
        balanceRepository.save(PolicyAccountBalance.builder()
            .policyAccountId(policyAccountId)
            .confirmedEaBalance(15_000L)
            .reservedExposure(0L)
            .availableBalance(15_000L)
            .build());

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(threadCount);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger insufficientBalanceFailures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    AddEndorsementRequest request = buildRequest("race-" + idx);
                    endorsementService.addEndorsement(policyAccountId, request);
                    successes.incrementAndGet();
                } catch (InsufficientBalanceException e) {
                    insufficientBalanceFailures.incrementAndGet();
                } catch (Exception ignored) {
                    // unexpected — will be caught by assertion below
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = finishGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();

        // Exactly 3 succeed, 17 rejected
        assertThat(successes.get()).isEqualTo(3);
        assertThat(insufficientBalanceFailures.get()).isEqualTo(17);
        assertThat(successes.get() + insufficientBalanceFailures.get())
            .as("All threads accounted for").isEqualTo(threadCount);

        // Balance invariant: available >= 0, reservedExposure == successes * premium
        PolicyAccountBalance finalBalance = balanceRepository.findById(policyAccountId).orElseThrow();
        assertThat(finalBalance.getAvailableBalance())
            .as("Available balance must never go negative")
            .isGreaterThanOrEqualTo(0L);
        assertThat(finalBalance.getReservedExposure())
            .as("Reserved must equal successes × premium")
            .isEqualTo(successes.get() * 5_000L);
        assertThat(finalBalance.getConfirmedEaBalance() - finalBalance.getReservedExposure())
            .as("confirmedEa - reserved = available (balance equation)")
            .isEqualTo(finalBalance.getAvailableBalance());
    }

    // ─────────────────────────────────────────────────────────────
    // Test 4: Optimistic lock version increments correctly
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sequential endorsements increment the @Version column correctly")
    void sequentialEndorsements_versionIncrementsCorrectly() {
        balanceRepository.save(PolicyAccountBalance.builder()
            .policyAccountId(policyAccountId)
            .confirmedEaBalance(50_000L)
            .reservedExposure(0L)
            .availableBalance(50_000L)
            .build());

        Long initialVersion = balanceRepository.findById(policyAccountId).orElseThrow().getVersion();

        // Create 3 sequential endorsements
        for (int i = 0; i < 3; i++) {
            endorsementService.addEndorsement(policyAccountId, buildRequest("seq-" + i));
        }

        Long finalVersion = balanceRepository.findById(policyAccountId).orElseThrow().getVersion();
        assertThat(finalVersion).as("Version should increment once per balance update")
            .isEqualTo(initialVersion + 3);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private AddEndorsementRequest buildRequest(String idempotencyKey) {
        var member = new AddEndorsementRequest.MemberDetails();
        member.setEmployeeCode("EMP-" + idempotencyKey);
        member.setFullName("Test Employee " + idempotencyKey);
        member.setDob(LocalDate.of(1990, 1, 15)); // age ~36 — within 18-65 band
        member.setMemberType("SELF");
        member.setGender("MALE");

        var req = new AddEndorsementRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setEffectiveDate(LocalDate.now());
        req.setRequestedByActor("EMPLOYER");
        req.setRequestedById("test-employer-001");
        req.setMember(member);
        return req;
    }
}
