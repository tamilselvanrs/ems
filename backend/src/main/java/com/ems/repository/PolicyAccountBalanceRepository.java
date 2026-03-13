package com.ems.repository;

import com.ems.domain.model.PolicyAccountBalance;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyAccountBalanceRepository extends JpaRepository<PolicyAccountBalance, UUID> {

    /**
     * Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on the balance row.
     * Serializes concurrent reserve/release/settle operations for the same policy account.
     * Lock timeout: 5 s — fails fast rather than queuing indefinitely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT b FROM PolicyAccountBalance b WHERE b.policyAccountId = :policyAccountId")
    Optional<PolicyAccountBalance> findWithLockById(@Param("policyAccountId") UUID policyAccountId);
}
