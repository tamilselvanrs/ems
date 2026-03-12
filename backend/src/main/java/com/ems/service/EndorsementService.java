package com.ems.service;

import com.ems.domain.enums.EndorsementStatus;
import com.ems.domain.enums.LedgerEntryType;
import com.ems.domain.enums.RequestType;
import com.ems.domain.model.EndorsementRequest;
import com.ems.domain.model.PolicyAccountBalance;
import com.ems.dto.request.AddEndorsementRequest;
import com.ems.dto.response.EndorsementResponse;
import com.ems.exception.EffectiveDateValidationException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.mapper.EndorsementMapper;
import com.ems.repository.EndorsementRequestRepository;
import com.ems.repository.InsurerConfigRepository;
import com.ems.repository.PolicyAccountBalanceRepository;
import com.ems.repository.PolicyAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EndorsementService {

    private final EndorsementRequestRepository endorsementRequestRepository;
    private final PolicyAccountRepository policyAccountRepository;
    private final PolicyAccountBalanceRepository balanceRepository;
    private final InsurerConfigRepository insurerConfigRepository;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final SubmissionRouter submissionRouter;
    private final EndorsementMapper endorsementMapper;

    /**
     * Creates an ADD endorsement request.
     *
     * Idempotency: if a request with the same idempotency_key already exists for this
     * policy_account, returns the existing request without creating a duplicate.
     *
     * Flow:
     *  1. Idempotency check
     *  2. Validate effective_date window
     *  3. Load insurer config for submission mode
     *  4. Create endorsement_request [DRAFT]
     *  5. Validate balance (for ADD type)
     *  6. Transition to VALIDATED + reserve balance
     *  7. Write RESERVE ledger entry (same transaction)
     *  8. Write audit log
     *  9. Async: route to SubmissionRouter
     */
    @Transactional
    public EndorsementResponse addEndorsement(UUID policyAccountId, AddEndorsementRequest request) {
        // 1. Idempotency check
        Optional<EndorsementRequest> existing = endorsementRequestRepository
            .findByPolicyAccountIdAndIdempotencyKey(policyAccountId, request.getIdempotencyKey());
        if (existing.isPresent()) {
            log.info("Returning existing endorsement for idempotency_key={}", request.getIdempotencyKey());
            return endorsementMapper.toResponse(existing.get());
        }

        // 2. Load policy account
        var policyAccount = policyAccountRepository.findById(policyAccountId)
            .orElseThrow(() -> new ResourceNotFoundException("PolicyAccount", policyAccountId));

        // 3. Load insurer config for effective date validation and submission mode
        var insurerConfig = insurerConfigRepository.findByInsurerId(policyAccount.getInsurerId())
            .orElseThrow(() -> new ResourceNotFoundException("InsurerConfig", policyAccount.getInsurerId()));

        // 4. Validate effective date
        validateEffectiveDate(request.getEffectiveDate(), insurerConfig.getBackdateWindowDays());

        // 5. Build endorsement request [DRAFT]
        var endorsementRequest = EndorsementRequest.builder()
            .policyAccountId(policyAccountId)
            .requestType(RequestType.ADD)
            .effectiveDate(request.getEffectiveDate())
            .requestedByActor(request.getRequestedByActor())
            .requestedById(request.getRequestedById())
            .submissionMode(insurerConfig.getExecutionMode())
            .idempotencyKey(request.getIdempotencyKey())
            .payload(buildPayload(request))
            .currentStatus(EndorsementStatus.DRAFT)
            .build();

        // 6. Validate balance for ADD — reserve if sufficient
        PolicyAccountBalance balance = balanceRepository.findById(policyAccountId)
            .orElseThrow(() -> new ResourceNotFoundException("PolicyAccountBalance", policyAccountId));

        long estimatedPremium = request.getEstimatedPremium();
        balance.reserve(estimatedPremium); // throws InsufficientBalanceException if insufficient

        // 7. Transition to VALIDATED
        endorsementRequest.transitionTo(EndorsementStatus.VALIDATED);

        // 8. Persist in one transaction
        endorsementRequest = endorsementRequestRepository.save(endorsementRequest);
        balanceRepository.save(balance);

        // 9. Write RESERVE ledger entry (same transaction)
        ledgerService.writeEntry(
            policyAccountId,
            endorsementRequest.getEndorsementRequestId(),
            LedgerEntryType.RESERVE,
            estimatedPremium,
            policyAccount.getCurrencyCode(),
            request.getEffectiveDate()
        );

        // 10. Audit
        auditService.log(
            request.getRequestedById(),
            request.getRequestedByActor(),
            "CREATE_ENDORSEMENT",
            "endorsement_request",
            endorsementRequest.getEndorsementRequestId().toString(),
            Map.of("request_type", "ADD", "effective_date", request.getEffectiveDate())
        );

        log.info("Endorsement request created: id={}, status={}, policyAccountId={}",
            endorsementRequest.getEndorsementRequestId(),
            endorsementRequest.getCurrentStatus(),
            policyAccountId);

        // 11. Async submission routing (fire-and-forget in Phase 1)
        submissionRouter.routeAsync(endorsementRequest.getEndorsementRequestId());

        return endorsementMapper.toResponse(endorsementRequest);
    }

    @Transactional(readOnly = true)
    public EndorsementResponse getEndorsement(UUID endorsementRequestId) {
        return endorsementRequestRepository.findById(endorsementRequestId)
            .map(endorsementMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("EndorsementRequest", endorsementRequestId));
    }

    @Transactional(readOnly = true)
    public java.util.List<EndorsementResponse> listEndorsements(UUID policyAccountId, EndorsementStatus status) {
        var requests = (status != null)
            ? endorsementRequestRepository.findByPolicyAccountIdAndCurrentStatus(policyAccountId, status)
            : endorsementRequestRepository.findByPolicyAccountId(policyAccountId);
        return requests.stream().map(endorsementMapper::toResponse).toList();
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private void validateEffectiveDate(LocalDate effectiveDate, int backdateWindowDays) {
        LocalDate earliest = LocalDate.now().minusDays(backdateWindowDays);
        if (effectiveDate.isBefore(earliest)) {
            throw new EffectiveDateValidationException(effectiveDate, backdateWindowDays);
        }
    }

    private Map<String, Object> buildPayload(AddEndorsementRequest request) {
        return Map.of(
            "full_name", request.getMember().getFullName(),
            "dob", request.getMember().getDob().toString(),
            "gender", request.getMember().getGender(),
            "member_type", request.getMember().getMemberType(),
            "employee_code", request.getMember().getEmployeeCode()
        );
    }
}
