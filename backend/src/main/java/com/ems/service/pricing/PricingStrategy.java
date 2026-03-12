package com.ems.service.pricing;

public interface PricingStrategy {

    boolean supports(PricingContext context);

    long calculatePremium(PricingContext context);
}
