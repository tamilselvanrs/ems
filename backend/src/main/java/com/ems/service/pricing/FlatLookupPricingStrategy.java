package com.ems.service.pricing;

import com.ems.exception.PricingRuleNotFoundException;
import com.ems.repository.PolicyPricingRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FlatLookupPricingStrategy implements PricingStrategy {

    private final PolicyPricingRuleRepository pricingRuleRepository;

    @Override
    public boolean supports(PricingContext context) {
        return true;
    }

    @Override
    public long calculatePremium(PricingContext context) {
        var rules = pricingRuleRepository.findMatchingRules(
            context.policyAccountId(),
            context.requestType(),
            context.memberType(),
            context.age(),
            context.gender()
        );

        if (rules.isEmpty()) {
            throw new PricingRuleNotFoundException(
                context.policyAccountId(),
                context.requestType().name(),
                context.memberType(),
                context.age(),
                context.gender()
            );
        }

        var matched = rules.get(0);
        log.debug("Matched pricing rule: id={}, basePremium={}, ageBand=[{}-{}], gender={}",
            matched.getPricingRuleId(), matched.getBasePremium(),
            matched.getAgeBandMin(), matched.getAgeBandMax(), matched.getGender());

        return matched.getBasePremium();
    }
}
