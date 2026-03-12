package com.ems.service;

import com.ems.domain.enums.RequestType;
import com.ems.exception.PricingRuleNotFoundException;
import com.ems.service.pricing.PricingContext;
import com.ems.service.pricing.PricingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private PricingStrategy pricingStrategy;

    private PricingService pricingService;
    private UUID policyAccountId;

    @BeforeEach
    void setUp() {
        pricingService = new PricingService(List.of(pricingStrategy));
        policyAccountId = UUID.randomUUID();
    }

    @Test
    void derivePremium_givenMatchingStrategy_returnsCalculatedPremium() {
        when(pricingStrategy.supports(any(PricingContext.class))).thenReturn(true);
        when(pricingStrategy.calculatePremium(any(PricingContext.class))).thenReturn(220_000L);

        long premium = pricingService.derivePremium(
            policyAccountId,
            RequestType.ADD,
            "SELF",
            LocalDate.of(1995, 5, 10),
            "MALE",
            LocalDate.of(2026, 5, 10)
        );

        assertThat(premium).isEqualTo(220_000L);
        verify(pricingStrategy).calculatePremium(any(PricingContext.class));
    }

    @Test
    void derivePremium_givenNoSupportingStrategy_throwsPricingRuleNotFoundException() {
        when(pricingStrategy.supports(any(PricingContext.class))).thenReturn(false);

        assertThatThrownBy(() -> pricingService.derivePremium(
            policyAccountId,
            RequestType.ADD,
            "SELF",
            LocalDate.of(1995, 5, 10),
            "MALE",
            LocalDate.of(2026, 5, 10)
        )).isInstanceOf(PricingRuleNotFoundException.class);
    }

    @Test
    void derivePremium_givenAgeBoundary_buildsContextWithExpectedAge() {
        when(pricingStrategy.supports(any(PricingContext.class))).thenReturn(true);
        when(pricingStrategy.calculatePremium(any(PricingContext.class))).thenReturn(250_000L);

        pricingService.derivePremium(
            policyAccountId,
            RequestType.ADD,
            "SELF",
            LocalDate.of(2008, 3, 13),
            "FEMALE",
            LocalDate.of(2026, 3, 12)
        );

        ArgumentCaptor<PricingContext> contextCaptor = ArgumentCaptor.forClass(PricingContext.class);
        verify(pricingStrategy).supports(contextCaptor.capture());
        assertThat(contextCaptor.getValue().age()).isEqualTo(17);
    }
}
