package com.ems.repository;

import com.ems.domain.enums.EndorsementStatus;
import com.ems.domain.model.EndorsementRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EndorsementRequestRepository extends JpaRepository<EndorsementRequest, UUID> {

    Optional<EndorsementRequest> findByPolicyAccountIdAndIdempotencyKey(UUID policyAccountId, String idempotencyKey);

    List<EndorsementRequest> findByPolicyAccountId(UUID policyAccountId);

    List<EndorsementRequest> findByPolicyAccountIdAndCurrentStatus(UUID policyAccountId, EndorsementStatus currentStatus);
}
