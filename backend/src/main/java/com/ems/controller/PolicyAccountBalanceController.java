package com.ems.controller;

import com.ems.dto.response.PolicyAccountBalanceResponse;
import com.ems.service.EndorsementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/policy-accounts/{policyAccountId}")
@RequiredArgsConstructor
@Tag(name = "Policy Account", description = "Policy account balances and metadata")
public class PolicyAccountBalanceController {

    private final EndorsementService endorsementService;

    @GetMapping("/balance")
    @Operation(summary = "Get policy account balance")
    public ResponseEntity<PolicyAccountBalanceResponse> getBalance(
        @PathVariable UUID policyAccountId
    ) {
        return ResponseEntity.ok(endorsementService.getPolicyAccountBalance(policyAccountId));
    }
}
