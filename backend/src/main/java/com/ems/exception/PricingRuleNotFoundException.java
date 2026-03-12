package com.ems.exception;

import java.util.UUID;

public class PricingRuleNotFoundException extends RuntimeException {
    public PricingRuleNotFoundException(UUID policyAccountId, String requestType,
                                        String memberType, int age, String gender) {
        super(String.format(
            "No active pricing rule found for policyAccountId=%s, requestType=%s, memberType=%s, age=%d, gender=%s",
            policyAccountId, requestType, memberType, age, gender));
    }
}
