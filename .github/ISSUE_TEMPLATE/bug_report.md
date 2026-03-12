---
name: Bug Report
about: Report a bug or unexpected behavior
title: '[BUG] '
labels: bug
assignees: ''
---

## Summary
<!-- One-sentence description of the issue -->

## Domain Area
<!-- Check all that apply -->
- [ ] Endorsement lifecycle
- [ ] Balance / Ledger
- [ ] Insurer submission (Realtime)
- [ ] Insurer submission (Batch)
- [ ] Reconciliation
- [ ] UI / Frontend
- [ ] Onboarding / Config

## Steps to Reproduce
1. 
2. 
3. 

## Expected Behavior
<!-- What should happen -->

## Actual Behavior
<!-- What actually happens -->

## Environment
- [ ] Local (Docker Compose)
- [ ] Staging
- [ ] Production

## Relevant Logs / Error Messages
```
Paste logs here
```

## Related Invariants (if applicable)
<!-- e.g., "Violates: available_balance = confirmed_ea_balance - reserved_exposure" -->

## For Agents Picking Up This Issue
<!-- Agent instructions — fill in when assigning to an AI agent -->
**Affected files (if known):**
- 

**Do NOT change:**
- 

**Must verify:**
- [ ] All tests pass after fix
- [ ] Ledger invariant maintained (if balance-related)
- [ ] State machine rules respected (if endorsement status-related)
