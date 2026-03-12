package com.ems.domain.model;

import com.ems.domain.enums.EndorsementStatus;
import com.ems.domain.enums.ExecutionMode;
import com.ems.domain.enums.RequestType;
import com.ems.exception.InvalidStateTransitionException;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "endorsement_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndorsementRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "endorsement_request_id")
    private UUID endorsementRequestId;

    @Column(name = "policy_account_id", nullable = false)
    private UUID policyAccountId;

    @Column(name = "policy_member_id")
    private UUID policyMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private RequestType requestType;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "requested_by_actor", nullable = false, length = 50)
    private String requestedByActor;

    @Column(name = "requested_by_id", nullable = false)
    private String requestedById;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 5;

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_mode", nullable = false, length = 20)
    private ExecutionMode submissionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 50)
    @Builder.Default
    private EndorsementStatus currentStatus = EndorsementStatus.DRAFT;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "source_ref")
    private String sourceRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Transitions the request to a new status, enforcing the state machine.
     * Throws InvalidStateTransitionException for invalid transitions.
     */
    public void transitionTo(EndorsementStatus next) {
        if (!this.currentStatus.canTransitionTo(next)) {
            throw new InvalidStateTransitionException(
                String.format("Cannot transition endorsement_request %s from %s to %s",
                    endorsementRequestId, currentStatus, next)
            );
        }
        this.currentStatus = next;
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isTerminal() {
        return currentStatus.isTerminal();
    }
}
