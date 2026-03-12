package com.ems.service;

import com.ems.domain.enums.RequestType;
import com.ems.exception.PricingRuleNotFoundException;
import com.ems.service.pricing.PricingContext;
import com.ems.service.pricing.PricingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    private final List<PricingStrategy> strategies;

    public long derivePremium(UUID policyAccountId, RequestType requestType,
                              String memberType, LocalDate dob, String gender,
                              LocalDate effectiveDate) {
        var context = new PricingContext(policyAccountId, requestType, memberType,
                                        dob, gender, effectiveDate);

        log.debug("Deriving premium: policyAccountId={}, requestType={}, memberType={}, age={}, gender={}",
            policyAccountId, requestType, memberType, context.age(), gender);

        return strategies.stream()
            .filter(s -> s.supports(context))
            .findFirst()
            .map(s -> s.calculatePremium(context))
            .orElseThrow(() -> new PricingRuleNotFoundException(
                policyAccountId, requestType.name(), memberType, context.age(), gender));
    }
}
