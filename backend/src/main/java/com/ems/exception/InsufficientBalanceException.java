package com.ems.exception;

import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(UUID policyAccountId, long available, long required) {
        super(String.format(
            "Insufficient balance for policy account %s: available=%d, required=%d",
            policyAccountId, available, required
        ));
    }
}
