package com.ems.exception;
import java.time.LocalDate;
public class EffectiveDateValidationException extends RuntimeException {
    public EffectiveDateValidationException(LocalDate effectiveDate, int backdateWindowDays) {
        super(String.format("Effective date %s is outside allowed window of %d days", effectiveDate, backdateWindowDays));
    }
}
