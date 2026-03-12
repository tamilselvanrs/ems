// src/types/endorsement.ts

export type EndorsementStatus =
  | 'DRAFT'
  | 'VALIDATED'
  | 'VALIDATION_FAILED'
  | 'QUEUED'
  | 'SUBMITTED'
  | 'INSURER_PROCESSING'
  | 'EXECUTED'
  | 'FAILED_RETRYABLE'
  | 'FAILED_TERMINAL'
  | 'CANCELLED';

export type RequestType = 'ADD' | 'DELETE' | 'UPDATE';
export type ExecutionMode = 'REALTIME' | 'BATCH';
export type MemberType = 'SELF' | 'DEPENDENT';

export interface MemberDetails {
  employee_code: string;
  full_name: string;
  dob: string; // ISO date string
  member_type: MemberType;
  gender?: 'MALE' | 'FEMALE' | 'OTHER';
  parent_employee_code?: string;
  relationship_type?: string;
}

export interface AddEndorsementRequest {
  idempotency_key: string;
  effective_date: string; // ISO date string
  requested_by_actor: 'EMPLOYER' | 'EMPLOYEE' | 'SYSTEM';
  requested_by_id: string;
  member: MemberDetails;
}

export interface EndorsementResponse {
  endorsement_request_id: string;
  policy_account_id: string;
  request_type: RequestType;
  effective_date: string;
  current_status: EndorsementStatus;
  submission_mode: ExecutionMode;
  retry_count: number;
  last_error_code?: string;
  last_error_message?: string;
  estimated_premium?: number | null;
  idempotency_key: string;
  created_at: string;
  updated_at: string;
  is_existing: boolean;
}

export interface PremiumPreviewRequest {
  effective_date: string; // ISO date string
  member: {
    dob: string;
    member_type: MemberType;
    gender?: 'MALE' | 'FEMALE' | 'OTHER';
  };
}

export interface PremiumPreviewResponse {
  estimated_premium: number;
  available_balance: number;
  currency_code: string;
}

export interface PolicyAccountBalance {
  policy_account_id: string;
  confirmed_ea_balance: number;
  reserved_exposure: number;
  available_balance: number;
  pending_credit: number;
  pending_debit: number;
  last_insurer_balance_sync_at?: string;
  last_reconciled_at?: string;
  drift_amount: number;
  updated_at: string;
}

export const TERMINAL_STATUSES: EndorsementStatus[] = ['EXECUTED', 'FAILED_TERMINAL', 'CANCELLED'];

export const STATUS_LABELS: Record<EndorsementStatus, string> = {
  DRAFT: 'Draft',
  VALIDATED: 'Validated',
  VALIDATION_FAILED: 'Validation Failed',
  QUEUED: 'Queued',
  SUBMITTED: 'Submitted',
  INSURER_PROCESSING: 'Processing',
  EXECUTED: 'Executed',
  FAILED_RETRYABLE: 'Failed (Retrying)',
  FAILED_TERMINAL: 'Failed',
  CANCELLED: 'Cancelled',
};

export const STATUS_COLORS: Record<EndorsementStatus, string> = {
  DRAFT: 'bg-gray-100 text-gray-700',
  VALIDATED: 'bg-blue-100 text-blue-700',
  VALIDATION_FAILED: 'bg-red-100 text-red-700',
  QUEUED: 'bg-yellow-100 text-yellow-700',
  SUBMITTED: 'bg-indigo-100 text-indigo-700',
  INSURER_PROCESSING: 'bg-purple-100 text-purple-700',
  EXECUTED: 'bg-green-100 text-green-700',
  FAILED_RETRYABLE: 'bg-orange-100 text-orange-700',
  FAILED_TERMINAL: 'bg-red-100 text-red-700',
  CANCELLED: 'bg-gray-100 text-gray-500',
};
