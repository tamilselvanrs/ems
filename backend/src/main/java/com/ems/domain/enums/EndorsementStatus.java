package com.ems.domain.enums;

import java.util.Set;

public enum EndorsementStatus {
    DRAFT,
    VALIDATED,
    VALIDATION_FAILED,
    QUEUED,
    SUBMITTED,
    INSURER_PROCESSING,
    EXECUTED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    CANCELLED;

    private static final Set<EndorsementStatus> TERMINAL = Set.of(EXECUTED, FAILED_TERMINAL, CANCELLED);

    private static final java.util.Map<EndorsementStatus, Set<EndorsementStatus>> VALID_TRANSITIONS =
        java.util.Map.of(
            DRAFT,               Set.of(VALIDATED, VALIDATION_FAILED, CANCELLED),
            VALIDATED,           Set.of(QUEUED, CANCELLED),
            VALIDATION_FAILED,   Set.of(DRAFT, CANCELLED),
            QUEUED,              Set.of(SUBMITTED, CANCELLED),
            SUBMITTED,           Set.of(INSURER_PROCESSING, EXECUTED, FAILED_RETRYABLE, FAILED_TERMINAL),
            INSURER_PROCESSING,  Set.of(EXECUTED, FAILED_RETRYABLE, FAILED_TERMINAL),
            FAILED_RETRYABLE,    Set.of(QUEUED, FAILED_TERMINAL),
            EXECUTED,            Set.of(),
            FAILED_TERMINAL,     Set.of(),
            CANCELLED,           Set.of()
        );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(EndorsementStatus next) {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }
}
