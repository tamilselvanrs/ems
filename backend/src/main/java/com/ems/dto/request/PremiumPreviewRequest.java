package com.ems.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PremiumPreviewRequest {

    @NotNull(message = "effective_date is required")
    @JsonProperty("effective_date")
    private LocalDate effectiveDate;

    @NotNull(message = "member is required")
    @Valid
    private MemberDetails member;

    @Data
    public static class MemberDetails {
        @NotNull(message = "member.dob is required")
        private LocalDate dob;

        @NotBlank(message = "member.member_type is required")
        @Pattern(regexp = "SELF|DEPENDENT", message = "member_type must be SELF or DEPENDENT")
        @JsonProperty("member_type")
        private String memberType;

        @Pattern(regexp = "MALE|FEMALE|OTHER", message = "gender must be MALE, FEMALE, or OTHER")
        private String gender;
    }
}
