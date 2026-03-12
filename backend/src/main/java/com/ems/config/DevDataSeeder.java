package com.ems.config;

import com.ems.domain.enums.ExecutionMode;
import com.ems.domain.model.InsurerConfig;
import com.ems.domain.model.PolicyAccount;
import com.ems.domain.model.PolicyAccountBalance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Component
@Profile("local")
@Slf4j
public class DevDataSeeder implements CommandLineRunner {

    static final UUID POLICY_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID INSURER_ID        = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void run(String... args) {
        em.persist(InsurerConfig.builder()
                .insurerConfigId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
                .insurerId(INSURER_ID)
                .insurerName("Demo Insurer")
                .executionMode(ExecutionMode.REALTIME)
                .backdateWindowDays(30)
                .isActive(true)
                .build());

        em.persist(PolicyAccount.builder()
                .policyAccountId(POLICY_ACCOUNT_ID)
                .insurerId(INSURER_ID)
                .employerId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .policyNumber("POL-DEMO-001")
                .currencyCode("INR")
                .policyStartDate(LocalDate.now().withDayOfYear(1))
                .policyEndDate(LocalDate.now().withDayOfYear(1).plusYears(1).minusDays(1))
                .isActive(true)
                .build());

        em.persist(PolicyAccountBalance.builder()
                .policyAccountId(POLICY_ACCOUNT_ID)
                .confirmedEaBalance(10_000_000L)
                .reservedExposure(0L)
                .availableBalance(10_000_000L)
                .driftAmount(0L)
                .build());

        log.info("Dev seed complete — policyAccountId={}, available balance=₹100000", POLICY_ACCOUNT_ID);
    }
}
