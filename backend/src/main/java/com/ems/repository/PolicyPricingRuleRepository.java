package com.ems.repository;

import com.ems.domain.enums.RequestType;
import com.ems.domain.model.PolicyPricingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PolicyPricingRuleRepository extends JpaRepository<PolicyPricingRule, UUID> {

    @Query("""
        SELECT p FROM PolicyPricingRule p
        WHERE p.policyAccountId = :policyAccountId
          AND p.requestType = :requestType
          AND p.memberType = :memberType
          AND p.isActive = true
          AND p.ageBandMin <= :age
          AND p.ageBandMax >= :age
          AND (p.gender IS NULL OR p.gender = :gender)
        ORDER BY p.gender DESC NULLS LAST
        """)
    List<PolicyPricingRule> findMatchingRules(
        @Param("policyAccountId") UUID policyAccountId,
        @Param("requestType") RequestType requestType,
        @Param("memberType") String memberType,
        @Param("age") int age,
        @Param("gender") String gender);
}
