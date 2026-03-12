package com.ems.dto.response;

import com.ems.domain.enums.EndorsementStatus;
import com.ems.domain.enums.ExecutionMode;
import com.ems.domain.enums.RequestType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class EndorsementResponse {

    @JsonProperty("endorsement_request_id")
    private UUID endorsementRequestId;

    @JsonProperty("policy_account_id")
    private UUID policyAccountId;

    @JsonProperty("request_type")
    private RequestType requestType;

    @JsonProperty("effective_date")
    private LocalDate effectiveDate;

    @JsonProperty("current_status")
    private EndorsementStatus currentStatus;

    @JsonProperty("submission_mode")
    private ExecutionMode submissionMode;

    @JsonProperty("retry_count")
    private Integer retryCount;

    @JsonProperty("last_error_code")
    private String lastErrorCode;

    @JsonProperty("last_error_message")
    private String lastErrorMessage;

    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    /** True if this response was returned due to an idempotency hit (existing record). */
    @JsonProperty("is_existing")
    @Builder.Default
    private boolean existing = false;
}
