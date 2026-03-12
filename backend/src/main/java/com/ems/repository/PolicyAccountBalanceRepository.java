package com.ems.repository;

import com.ems.domain.model.PolicyAccountBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PolicyAccountBalanceRepository extends JpaRepository<PolicyAccountBalance, UUID> {
}
