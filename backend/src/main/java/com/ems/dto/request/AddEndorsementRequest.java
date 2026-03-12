package com.ems.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AddEndorsementRequest {

    @NotBlank(message = "idempotency_key is required")
    @Size(max = 255)
    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    @NotNull(message = "effective_date is required")
    @JsonProperty("effective_date")
    private LocalDate effectiveDate;

    @NotBlank(message = "requested_by_actor is required")
    @Pattern(regexp = "EMPLOYER|EMPLOYEE|SYSTEM", message = "requested_by_actor must be EMPLOYER, EMPLOYEE, or SYSTEM")
    @JsonProperty("requested_by_actor")
    private String requestedByActor;

    @NotBlank(message = "requested_by_id is required")
    @JsonProperty("requested_by_id")
    private String requestedById;

    @NotNull(message = "member is required")
    @Valid
    private MemberDetails member;

    @Data
    public static class MemberDetails {
        @NotBlank
        @JsonProperty("employee_code")
        private String employeeCode;

        @NotBlank
        @JsonProperty("full_name")
        private String fullName;

        @NotNull
        private LocalDate dob;

        @NotBlank
        @Pattern(regexp = "SELF|DEPENDENT")
        @JsonProperty("member_type")
        private String memberType;

        @Pattern(regexp = "MALE|FEMALE|OTHER")
        private String gender;

        @JsonProperty("parent_employee_code")
        private String parentEmployeeCode;

        @JsonProperty("relationship_type")
        private String relationshipType;
    }
}
