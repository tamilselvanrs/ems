package com.ems.controller;

import com.ems.domain.enums.EndorsementStatus;
import com.ems.dto.request.AddEndorsementRequest;
import com.ems.dto.response.EndorsementResponse;
import com.ems.service.EndorsementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/policy-accounts/{policyAccountId}/endorsements")
@RequiredArgsConstructor
@Tag(name = "Endorsements", description = "Endorsement lifecycle management")
public class EndorsementController {

    private final EndorsementService endorsementService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit an ADD endorsement",
               description = "Creates a new member endorsement. Idempotent on idempotency_key.")
    public ResponseEntity<EndorsementResponse> addEndorsement(
        @PathVariable UUID policyAccountId,
        @Valid @RequestBody AddEndorsementRequest request
    ) {
        EndorsementResponse response = endorsementService.addEndorsement(policyAccountId, request);
        // 200 if idempotent hit (existing), 201 if newly created
        HttpStatus status = response.isExisting() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{endorsementRequestId}")
    @Operation(summary = "Get endorsement by ID")
    public ResponseEntity<EndorsementResponse> getEndorsement(
        @PathVariable UUID policyAccountId,
        @PathVariable UUID endorsementRequestId
    ) {
        return ResponseEntity.ok(endorsementService.getEndorsement(endorsementRequestId));
    }

    @GetMapping
    @Operation(summary = "List endorsements for a policy account")
    public ResponseEntity<List<EndorsementResponse>> listEndorsements(
        @PathVariable UUID policyAccountId,
        @RequestParam(required = false) EndorsementStatus status
    ) {
        return ResponseEntity.ok(endorsementService.listEndorsements(policyAccountId, status));
    }
}
