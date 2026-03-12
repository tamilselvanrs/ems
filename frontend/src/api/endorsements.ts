// src/api/endorsements.ts
import axios from 'axios';
import type {
  AddEndorsementRequest,
  EndorsementResponse,
  EndorsementStatus,
  PolicyAccountBalance,
  PremiumPreviewRequest,
  PremiumPreviewResponse,
} from '../types/endorsement';

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  headers: { 'Content-Type': 'application/json' },
});

// TODO: Replace stub token with real auth (Phase 2 — IdP integration)
apiClient.interceptors.request.use((config) => {
  config.headers['X-Employer-Id'] = 'stub-employer-id';
  return config;
});

export const endorsementApi = {
  add: (policyAccountId: string, request: AddEndorsementRequest): Promise<EndorsementResponse> =>
    apiClient
      .post<EndorsementResponse>(`/api/v1/policy-accounts/${policyAccountId}/endorsements`, request)
      .then((r) => r.data),

  get: (policyAccountId: string, endorsementRequestId: string): Promise<EndorsementResponse> =>
    apiClient
      .get<EndorsementResponse>(
        `/api/v1/policy-accounts/${policyAccountId}/endorsements/${endorsementRequestId}`
      )
      .then((r) => r.data),

  list: (policyAccountId: string, status?: EndorsementStatus): Promise<EndorsementResponse[]> => {
    const params = status ? { status } : {};
    return apiClient
      .get<EndorsementResponse[]>(`/api/v1/policy-accounts/${policyAccountId}/endorsements`, { params })
      .then((r) => r.data);
  },

  preview: (
    policyAccountId: string,
    request: PremiumPreviewRequest
  ): Promise<PremiumPreviewResponse> =>
    apiClient
      .post<PremiumPreviewResponse>(
        `/api/v1/policy-accounts/${policyAccountId}/endorsements/preview`,
        request
      )
      .then((r) => r.data),
};

export const balanceApi = {
  get: (policyAccountId: string): Promise<PolicyAccountBalance> =>
    apiClient
      .get<PolicyAccountBalance>(`/api/v1/policy-accounts/${policyAccountId}/balance`)
      .then((r) => r.data),
};
