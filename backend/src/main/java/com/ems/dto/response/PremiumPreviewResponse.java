package com.ems.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PremiumPreviewResponse {

    @JsonProperty("estimated_premium")
    private Long estimatedPremium;

    @JsonProperty("available_balance")
    private Long availableBalance;

    @JsonProperty("currency_code")
    private String currencyCode;
}
