package com.ems.domain.model;

import com.ems.domain.enums.RequestType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "policy_pricing_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyPricingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "pricing_rule_id")
    private UUID pricingRuleId;

    @Column(name = "policy_account_id", nullable = false)
    private UUID policyAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private RequestType requestType;

    @Column(name = "member_type", nullable = false, length = 20)
    private String memberType;

    @Column(name = "age_band_min", nullable = false)
    private Integer ageBandMin;

    @Column(name = "age_band_max", nullable = false)
    private Integer ageBandMax;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "base_premium", nullable = false)
    private Long basePremium;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
