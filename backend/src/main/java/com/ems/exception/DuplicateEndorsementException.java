package com.ems.exception;
public class DuplicateEndorsementException extends RuntimeException {
    public DuplicateEndorsementException(String idempotencyKey) {
        super("Endorsement already exists for idempotency key: " + idempotencyKey);
    }
}
