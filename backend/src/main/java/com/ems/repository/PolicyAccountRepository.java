package com.ems.repository;

import com.ems.domain.model.PolicyAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PolicyAccountRepository extends JpaRepository<PolicyAccount, UUID> {
}
