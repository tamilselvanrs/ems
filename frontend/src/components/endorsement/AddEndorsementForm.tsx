// src/components/endorsement/AddEndorsementForm.tsx
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { balanceApi, endorsementApi } from '../../api/endorsements';
import type { AddEndorsementRequest } from '../../types/endorsement';
import { v4 as uuidv4 } from 'uuid';

const memberSchema = z.object({
  employee_code: z.string().min(1, 'Employee code is required'),
  full_name: z.string().min(2, 'Full name is required'),
  dob: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Date of birth must be YYYY-MM-DD'),
  member_type: z.enum(['SELF', 'DEPENDENT']),
  gender: z.enum(['MALE', 'FEMALE', 'OTHER']).optional(),
  parent_employee_code: z.string().optional(),
  relationship_type: z.string().optional(),
});

const addEndorsementSchema = z.object({
  effective_date: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'Date must be YYYY-MM-DD')
    .refine((d) => new Date(d) <= new Date(), { message: 'Effective date cannot be in the future' }),
  member: memberSchema,
});

type FormData = z.infer<typeof addEndorsementSchema>;

interface Props {
  policyAccountId: string;
  onSuccess?: (endorsementRequestId: string) => void;
}

export function AddEndorsementForm({ policyAccountId, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const formatMoney = (amountInMinorUnits: number | null | undefined, currencyCode = 'INR') => {
    if (amountInMinorUnits == null) {
      return '—';
    }
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: currencyCode,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(amountInMinorUnits / 100);
  };

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
    reset,
    watch,
  } = useForm<FormData>({
    resolver: zodResolver(addEndorsementSchema),
    defaultValues: {
      effective_date: new Date().toISOString().split('T')[0],
      member: { member_type: 'SELF' },
    },
  });

  const memberType = watch('member.member_type');
  const memberDob = watch('member.dob');
  const memberGender = watch('member.gender');
  const effectiveDate = watch('effective_date');

  const balanceQuery = useQuery({
    queryKey: ['balance', policyAccountId],
    queryFn: () => balanceApi.get(policyAccountId),
  });

  const premiumPreviewEnabled = Boolean(policyAccountId && effectiveDate && memberDob && memberType);
  const premiumPreviewQuery = useQuery({
    queryKey: ['premium-preview', policyAccountId, effectiveDate, memberDob, memberType, memberGender ?? ''],
    enabled: premiumPreviewEnabled,
    queryFn: () =>
      endorsementApi.preview(policyAccountId, {
        effective_date: effectiveDate,
        member: {
          dob: memberDob,
          member_type: memberType,
          gender: memberGender || undefined,
        },
      }),
    retry: false,
  });

  const availableBalance = balanceQuery.data?.available_balance;
  const estimatedPremium = premiumPreviewQuery.data?.estimated_premium;
  const hasInsufficientBalance =
    typeof availableBalance === 'number' &&
    typeof estimatedPremium === 'number' &&
    availableBalance < estimatedPremium;

  const mutation = useMutation({
    mutationFn: (data: FormData) => {
      const request: AddEndorsementRequest = {
        ...data,
        idempotency_key: uuidv4(), // unique per submission
        requested_by_actor: 'EMPLOYER',
        requested_by_id: 'stub-employer-id', // TODO: from auth context
      };
      return endorsementApi.add(policyAccountId, request);
    },
    onSuccess: (response) => {
      queryClient.invalidateQueries({ queryKey: ['endorsements', policyAccountId] });
      queryClient.invalidateQueries({ queryKey: ['balance', policyAccountId] });
      reset();
      onSuccess?.(response.endorsement_request_id);
    },
  });

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-6 shadow-sm">
      <h2 className="text-lg font-semibold text-gray-900 mb-6">Add Member to Policy</h2>

      <div className="grid grid-cols-2 gap-4 mb-6">
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
          <p className="text-xs font-medium uppercase tracking-wide text-gray-500">Available Balance</p>
          <p className="mt-1 text-lg font-semibold text-gray-900">
            {formatMoney(balanceQuery.data?.available_balance)}
          </p>
          {balanceQuery.isError && (
            <p className="mt-1 text-xs text-red-600">Unable to load available balance.</p>
          )}
        </div>

        <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
          <p className="text-xs font-medium uppercase tracking-wide text-gray-500">Estimated Premium</p>
          <p className="mt-1 text-lg font-semibold text-gray-900">
            {premiumPreviewQuery.isSuccess
              ? formatMoney(
                  premiumPreviewQuery.data.estimated_premium,
                  premiumPreviewQuery.data.currency_code
                )
              : '—'}
          </p>
          {!premiumPreviewEnabled && (
            <p className="mt-1 text-xs text-gray-500">Fill effective date, DOB, and member type.</p>
          )}
          {premiumPreviewQuery.isError && (
            <p className="mt-1 text-xs text-red-600">
              {(premiumPreviewQuery.error as { response?: { data?: { detail?: string } } })?.response?.data
                ?.detail || 'No matching pricing rule for current details.'}
            </p>
          )}
        </div>
      </div>
      {hasInsufficientBalance && (
        <div className="mb-6 rounded-lg border border-amber-200 bg-amber-50 p-3">
          <p className="text-sm text-amber-800">
            Estimated premium exceeds available balance. Submission may fail with insufficient balance.
          </p>
        </div>
      )}

      <form onSubmit={handleSubmit((data) => mutation.mutate(data))} className="space-y-5">

        {/* Member Details */}
        <fieldset className="space-y-4">
          <legend className="text-sm font-medium text-gray-700 mb-3">Member Details</legend>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Employee Code <span className="text-red-500">*</span>
              </label>
              <input
                {...register('member.employee_code')}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="EMP-001"
              />
              {errors.member?.employee_code && (
                <p className="mt-1 text-xs text-red-600">{errors.member.employee_code.message}</p>
              )}
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Full Name <span className="text-red-500">*</span>
              </label>
              <input
                {...register('member.full_name')}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="John Doe"
              />
              {errors.member?.full_name && (
                <p className="mt-1 text-xs text-red-600">{errors.member.full_name.message}</p>
              )}
            </div>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Date of Birth <span className="text-red-500">*</span>
              </label>
              <input
                type="date"
                {...register('member.dob')}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              {errors.member?.dob && (
                <p className="mt-1 text-xs text-red-600">{errors.member.dob.message}</p>
              )}
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Member Type <span className="text-red-500">*</span>
              </label>
              <select
                {...register('member.member_type')}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="SELF">Self</option>
                <option value="DEPENDENT">Dependent</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Gender</label>
              <select
                {...register('member.gender')}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="">Select</option>
                <option value="MALE">Male</option>
                <option value="FEMALE">Female</option>
                <option value="OTHER">Other</option>
              </select>
            </div>
          </div>

          {memberType === 'DEPENDENT' && (
            <div className="grid grid-cols-2 gap-4 p-4 bg-blue-50 rounded-lg">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Parent Employee Code <span className="text-red-500">*</span>
                </label>
                <input
                  {...register('member.parent_employee_code')}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm"
                  placeholder="EMP-001"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Relationship
                </label>
                <select
                  {...register('member.relationship_type')}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm"
                >
                  <option value="">Select</option>
                  <option value="SPOUSE">Spouse</option>
                  <option value="CHILD">Child</option>
                  <option value="PARENT">Parent</option>
                </select>
              </div>
            </div>
          )}
        </fieldset>

        {/* Coverage Details */}
        <fieldset className="space-y-4 pt-4 border-t border-gray-100">
          <legend className="text-sm font-medium text-gray-700 mb-3">Coverage Details</legend>

          <div className="grid grid-cols-1 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Effective Date <span className="text-red-500">*</span>
              </label>
              <input
                type="date"
                {...register('effective_date')}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              {errors.effective_date && (
                <p className="mt-1 text-xs text-red-600">{errors.effective_date.message}</p>
              )}
            </div>
          </div>
          <p className="text-xs text-gray-500">
            Estimated premium above is derived from active pricing rules at the selected effective date.
          </p>
        </fieldset>

        {/* Error banner */}
        {mutation.isError && (
          <div className="rounded-lg bg-red-50 border border-red-200 p-4">
            <p className="text-sm text-red-700">
              {(mutation.error as { response?: { data?: { detail?: string } } })?.response?.data?.detail || 'Failed to submit endorsement. Please try again.'}
            </p>
          </div>
        )}

        {/* Submit */}
        <div className="flex justify-end pt-2">
          <button
            type="submit"
            disabled={isSubmitting || mutation.isPending}
            className="inline-flex items-center gap-2 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white font-medium px-5 py-2.5 rounded-lg text-sm transition-colors"
          >
            {mutation.isPending ? (
              <>
                <span className="h-4 w-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                Submitting...
              </>
            ) : (
              'Submit Endorsement'
            )}
          </button>
        </div>
      </form>
    </div>
  );
}
