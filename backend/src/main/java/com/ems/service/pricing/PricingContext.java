package com.ems.service.pricing;

import com.ems.domain.enums.RequestType;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

public record PricingContext(
    UUID policyAccountId,
    RequestType requestType,
    String memberType,
    LocalDate dob,
    String gender,
    LocalDate effectiveDate,
    int age
) {
    public PricingContext(UUID policyAccountId, RequestType requestType, String memberType,
                         LocalDate dob, String gender, LocalDate effectiveDate) {
        this(policyAccountId, requestType, memberType, dob, gender, effectiveDate,
             Period.between(dob, effectiveDate).getYears());
    }
}
