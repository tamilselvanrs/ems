import { useState } from 'react';
import { AddEndorsementForm } from './components/endorsement/AddEndorsementForm';

const STUB_POLICY_ACCOUNT_ID = '00000000-0000-0000-0000-000000000001';

export function App() {
  const [lastId, setLastId] = useState<string | null>(null);

  return (
    <div className="min-h-screen bg-gray-50 p-8">
      <div className="max-w-2xl mx-auto space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Endorsement Management</h1>
          <p className="text-sm text-gray-500 mt-1">Policy account: {STUB_POLICY_ACCOUNT_ID}</p>
        </div>

        <AddEndorsementForm
          policyAccountId={STUB_POLICY_ACCOUNT_ID}
          onSuccess={(id) => setLastId(id)}
        />

        {lastId && (
          <div className="rounded-lg bg-green-50 border border-green-200 p-4">
            <p className="text-sm text-green-700 font-medium">Endorsement submitted!</p>
            <p className="text-xs text-green-600 mt-1 font-mono">{lastId}</p>
          </div>
        )}
      </div>
    </div>
  );
}
