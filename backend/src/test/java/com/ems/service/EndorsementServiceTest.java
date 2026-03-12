package com.ems.service;

import com.ems.domain.enums.EndorsementStatus;
import com.ems.domain.enums.ExecutionMode;
import com.ems.domain.model.EndorsementRequest;
import com.ems.domain.model.PolicyAccountBalance;
import com.ems.dto.request.AddEndorsementRequest;
import com.ems.dto.request.PremiumPreviewRequest;
import com.ems.dto.response.EndorsementResponse;
import com.ems.dto.response.PolicyAccountBalanceResponse;
import com.ems.dto.response.PremiumPreviewResponse;
import com.ems.exception.EffectiveDateValidationException;
import com.ems.exception.InsufficientBalanceException;
import com.ems.exception.PricingRuleNotFoundException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.mapper.EndorsementMapper;
import com.ems.repository.EndorsementRequestRepository;
import com.ems.repository.InsurerConfigRepository;
import com.ems.repository.PolicyAccountBalanceRepository;
import com.ems.repository.PolicyAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EndorsementServiceTest {

    @Mock private EndorsementRequestRepository endorsementRequestRepository;
    @Mock private PolicyAccountRepository policyAccountRepository;
    @Mock private PolicyAccountBalanceRepository balanceRepository;
    @Mock private InsurerConfigRepository insurerConfigRepository;
    @Mock private LedgerService ledgerService;
    @Mock private AuditService auditService;
    @Mock private SubmissionRouter submissionRouter;
    @Mock private EndorsementMapper endorsementMapper;
    @Mock private PricingService pricingService;

    @InjectMocks
    private EndorsementService endorsementService;

    private UUID policyAccountId;
    private UUID insurerId;
    private AddEndorsementRequest validRequest;

    @BeforeEach
    void setUp() {
        policyAccountId = UUID.randomUUID();
        insurerId = UUID.randomUUID();

        validRequest = buildValidRequest();
    }

    @Test
    void addEndorsement_givenValidRequest_createsEndorsementWithValidatedStatus() {
        // Arrange
        var policyAccount = mockPolicyAccount();
        var insurerConfig = mockInsurerConfig(30);
        var balance = mockSufficientBalance(100_000L);
        var savedRequest = mockSavedEndorsementRequest();
        var expectedResponse = EndorsementResponse.builder()
            .endorsementRequestId(savedRequest.getEndorsementRequestId())
            .currentStatus(EndorsementStatus.VALIDATED)
            .build();

        when(endorsementRequestRepository.findByPolicyAccountIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(policyAccountRepository.findById(policyAccountId)).thenReturn(Optional.of(policyAccount));
        when(insurerConfigRepository.findByInsurerId(insurerId)).thenReturn(Optional.of(insurerConfig));
        when(balanceRepository.findWithLockById(policyAccountId)).thenReturn(Optional.of(balance));
        when(pricingService.derivePremium(any(), any(), any(), any(), any(), any())).thenReturn(5_000L);
        when(endorsementRequestRepository.save(any())).thenReturn(savedRequest);
        when(balanceRepository.save(any())).thenReturn(balance);
        when(endorsementMapper.toResponse(savedRequest)).thenReturn(expectedResponse);

        // Act
        EndorsementResponse response = endorsementService.addEndorsement(policyAccountId, validRequest);

        // Assert
        assertThat(response.getCurrentStatus()).isEqualTo(EndorsementStatus.VALIDATED);
        verify(ledgerService).writeEntry(any(), any(), any(), anyLong(), any(), any());
        verify(auditService).log(any(), any(), eq("CREATE_ENDORSEMENT"), any(), any(), any());
        verify(submissionRouter).routeAsync(any());
    }

    @Test
    void addEndorsement_givenDuplicateIdempotencyKey_returnsExistingRequest() {
        // Arrange
        var existing = mockSavedEndorsementRequest();
        var existingResponse = EndorsementResponse.builder()
            .endorsementRequestId(existing.getEndorsementRequestId())
            .existing(true)
            .build();

        when(endorsementRequestRepository.findByPolicyAccountIdAndIdempotencyKey(policyAccountId, validRequest.getIdempotencyKey()))
            .thenReturn(Optional.of(existing));
        when(endorsementMapper.toResponse(existing)).thenReturn(existingResponse);

        // Act
        EndorsementResponse response = endorsementService.addEndorsement(policyAccountId, validRequest);

        // Assert
        assertThat(response.isExisting()).isTrue();
        verify(policyAccountRepository, never()).findById(any());
        verify(ledgerService, never()).writeEntry(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void addEndorsement_givenInsufficientBalance_throwsInsufficientBalanceException() {
        // Arrange
        var policyAccount = mockPolicyAccount();
        var insurerConfig = mockInsurerConfig(30);
        var balance = mockInsufficientBalance(100L); // balance of 100 paisa, premium of 5000 paisa

        when(endorsementRequestRepository.findByPolicyAccountIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(policyAccountRepository.findById(policyAccountId)).thenReturn(Optional.of(policyAccount));
        when(insurerConfigRepository.findByInsurerId(insurerId)).thenReturn(Optional.of(insurerConfig));
        when(balanceRepository.findWithLockById(policyAccountId)).thenReturn(Optional.of(balance));
        when(pricingService.derivePremium(any(), any(), any(), any(), any(), any())).thenReturn(5_000L);

        // Act + Assert
        assertThatThrownBy(() -> endorsementService.addEndorsement(policyAccountId, validRequest))
            .isInstanceOf(InsufficientBalanceException.class);

        verify(endorsementRequestRepository, never()).save(any());
        verify(ledgerService, never()).writeEntry(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void addEndorsement_givenEffectiveDateOutsideBackdateWindow_throwsEffectiveDateValidationException() {
        // Arrange
        validRequest.setEffectiveDate(LocalDate.now().minusDays(60)); // 60 days ago, window is 30
        var policyAccount = mockPolicyAccount();
        var insurerConfig = mockInsurerConfig(30);

        when(endorsementRequestRepository.findByPolicyAccountIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(policyAccountRepository.findById(policyAccountId)).thenReturn(Optional.of(policyAccount));
        when(insurerConfigRepository.findByInsurerId(insurerId)).thenReturn(Optional.of(insurerConfig));

        // Act + Assert
        assertThatThrownBy(() -> endorsementService.addEndorsement(policyAccountId, validRequest))
            .isInstanceOf(EffectiveDateValidationException.class);
    }

    @Test
    void addEndorsement_givenNoPricingRule_throwsPricingRuleNotFoundException() {
        // Arrange
        var policyAccount = mockPolicyAccount();
        var insurerConfig = mockInsurerConfig(30);
        var balance = mockSufficientBalance(100_000L);

        when(endorsementRequestRepository.findByPolicyAccountIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(policyAccountRepository.findById(policyAccountId)).thenReturn(Optional.of(policyAccount));
        when(insurerConfigRepository.findByInsurerId(insurerId)).thenReturn(Optional.of(insurerConfig));
        when(balanceRepository.findWithLockById(policyAccountId)).thenReturn(Optional.of(balance));
        when(pricingService.derivePremium(any(), any(), any(), any(), any(), any()))
            .thenThrow(new PricingRuleNotFoundException(policyAccountId, "ADD", "SELF", 30, "MALE"));

        // Act + Assert
        assertThatThrownBy(() -> endorsementService.addEndorsement(policyAccountId, validRequest))
            .isInstanceOf(PricingRuleNotFoundException.class);

        verify(endorsementRequestRepository, never()).save(any());
        verify(ledgerService, never()).writeEntry(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void addEndorsement_givenPolicyAccountNotFound_throwsResourceNotFoundException() {
        // Arrange
        when(endorsementRequestRepository.findByPolicyAccountIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(policyAccountRepository.findById(policyAccountId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> endorsementService.addEndorsement(policyAccountId, validRequest))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("PolicyAccount");
    }

    @Test
    void getPolicyAccountBalance_givenExistingBalance_returnsBalanceResponse() {
        // Arrange
        PolicyAccountBalance balance = PolicyAccountBalance.builder()
            .policyAccountId(policyAccountId)
            .confirmedEaBalance(1_000_000L)
            .reservedExposure(250_000L)
            .availableBalance(750_000L)
            .pendingCredit(10_000L)
            .pendingDebit(20_000L)
            .driftAmount(0L)
            .updatedAt(OffsetDateTime.now())
            .build();

        when(balanceRepository.findById(policyAccountId)).thenReturn(Optional.of(balance));

        // Act
        PolicyAccountBalanceResponse response = endorsementService.getPolicyAccountBalance(policyAccountId);

        // Assert
        assertThat(response.getPolicyAccountId()).isEqualTo(policyAccountId);
        assertThat(response.getAvailableBalance()).isEqualTo(750_000L);
        assertThat(response.getConfirmedEaBalance()).isEqualTo(1_000_000L);
    }

    @Test
    void previewAddEndorsement_givenValidInput_returnsEstimatedPremiumAndAvailableBalance() {
        // Arrange
        var policyAccount = mockPolicyAccount();
        var insurerConfig = mockInsurerConfig(30);
        var balance = mockSufficientBalance(900_000L);
        var request = buildPremiumPreviewRequest();

        when(policyAccountRepository.findById(policyAccountId)).thenReturn(Optional.of(policyAccount));
        when(insurerConfigRepository.findByInsurerId(insurerId)).thenReturn(Optional.of(insurerConfig));
        when(balanceRepository.findById(policyAccountId)).thenReturn(Optional.of(balance));
        when(pricingService.derivePremium(any(), any(), any(), any(), any(), any())).thenReturn(250_000L);

        // Act
        PremiumPreviewResponse response = endorsementService.previewAddEndorsement(policyAccountId, request);

        // Assert
        assertThat(response.getEstimatedPremium()).isEqualTo(250_000L);
        assertThat(response.getAvailableBalance()).isEqualTo(900_000L);
        assertThat(response.getCurrencyCode()).isEqualTo("INR");
    }

    @Test
    void previewAddEndorsement_givenInvalidEffectiveDate_throwsEffectiveDateValidationException() {
        // Arrange
        var policyAccount = mockPolicyAccount();
        var insurerConfig = mockInsurerConfig(30);
        var request = buildPremiumPreviewRequest();
        request.setEffectiveDate(LocalDate.now().minusDays(45));

        when(policyAccountRepository.findById(policyAccountId)).thenReturn(Optional.of(policyAccount));
        when(insurerConfigRepository.findByInsurerId(insurerId)).thenReturn(Optional.of(insurerConfig));

        // Act + Assert
        assertThatThrownBy(() -> endorsementService.previewAddEndorsement(policyAccountId, request))
            .isInstanceOf(EffectiveDateValidationException.class);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private AddEndorsementRequest buildValidRequest() {
        var member = new AddEndorsementRequest.MemberDetails();
        member.setEmployeeCode("EMP-001");
        member.setFullName("John Doe");
        member.setDob(LocalDate.of(1990, 1, 15));
        member.setMemberType("SELF");
        member.setGender("MALE");

        var req = new AddEndorsementRequest();
        req.setIdempotencyKey("idem-key-" + UUID.randomUUID());
        req.setEffectiveDate(LocalDate.now());
        req.setRequestedByActor("EMPLOYER");
        req.setRequestedById("employer-id-001");
        req.setMember(member);
        return req;
    }

    private PremiumPreviewRequest buildPremiumPreviewRequest() {
        var member = new PremiumPreviewRequest.MemberDetails();
        member.setDob(LocalDate.of(1992, 6, 20));
        member.setMemberType("SELF");
        member.setGender("MALE");

        var request = new PremiumPreviewRequest();
        request.setEffectiveDate(LocalDate.now());
        request.setMember(member);
        return request;
    }

    private com.ems.domain.model.PolicyAccount mockPolicyAccount() {
        var pa = mock(com.ems.domain.model.PolicyAccount.class);
        when(pa.getInsurerId()).thenReturn(insurerId);
        when(pa.getCurrencyCode()).thenReturn("INR");
        return pa;
    }

    private com.ems.domain.model.InsurerConfig mockInsurerConfig(int backdateWindowDays) {
        var ic = mock(com.ems.domain.model.InsurerConfig.class);
        when(ic.getBackdateWindowDays()).thenReturn(backdateWindowDays);
        when(ic.getExecutionMode()).thenReturn(ExecutionMode.REALTIME);
        return ic;
    }

    private PolicyAccountBalance mockSufficientBalance(long available) {
        return PolicyAccountBalance.builder()
            .policyAccountId(policyAccountId)
            .confirmedEaBalance(available)
            .reservedExposure(0L)
            .availableBalance(available)
            .build();
    }

    private PolicyAccountBalance mockInsufficientBalance(long available) {
        return PolicyAccountBalance.builder()
            .policyAccountId(policyAccountId)
            .confirmedEaBalance(available)
            .reservedExposure(0L)
            .availableBalance(available)
            .build();
    }

    private EndorsementRequest mockSavedEndorsementRequest() {
        return EndorsementRequest.builder()
            .endorsementRequestId(UUID.randomUUID())
            .policyAccountId(policyAccountId)
            .currentStatus(EndorsementStatus.VALIDATED)
            .idempotencyKey(validRequest.getIdempotencyKey())
            .build();
    }
}
