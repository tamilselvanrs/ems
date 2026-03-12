import { describe, it, expect } from 'vitest';
import { TERMINAL_STATUSES, STATUS_LABELS, STATUS_COLORS } from './endorsement';

describe('endorsement constants', () => {
  it('TERMINAL_STATUSES contains expected values', () => {
    expect(TERMINAL_STATUSES).toContain('EXECUTED');
    expect(TERMINAL_STATUSES).toContain('FAILED_TERMINAL');
    expect(TERMINAL_STATUSES).toContain('CANCELLED');
  });

  it('STATUS_LABELS has a label for every status', () => {
    expect(Object.keys(STATUS_LABELS).length).toBe(10);
  });

  it('STATUS_COLORS has a color for every status', () => {
    expect(Object.keys(STATUS_COLORS).length).toBe(10);
  });
});
