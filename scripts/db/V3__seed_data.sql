-- V3__seed_data.sql
-- Local dev seed: 1 insurer (REALTIME), 1 insurer (BATCH), 1 employer, 1 policy account each

-- Insurers
INSERT INTO insurer (insurer_id, insurer_code, name, status, country_code) VALUES
    ('a1000000-0000-0000-0000-000000000001', 'INSURER_RT', 'Realtime Health Insurance Co.', 'ACTIVE', 'IN'),
    ('a1000000-0000-0000-0000-000000000002', 'INSURER_BATCH', 'Batch Health Insurance Co.', 'ACTIVE', 'IN');

-- Insurer Configs
INSERT INTO insurer_config (insurer_config_id, insurer_id, execution_mode, supports_webhook, qps_limit, backdate_window_days, status) VALUES
    ('b1000000-0000-0000-0000-000000000001', 'a1000000-0000-0000-0000-000000000001', 'REALTIME', TRUE, 20, 30, 'ACTIVE'),
    ('b1000000-0000-0000-0000-000000000002', 'a1000000-0000-0000-0000-000000000002', 'BATCH', FALSE, 5, 30, 'ACTIVE');

-- Employers
INSERT INTO employer (employer_id, tenant_key, legal_name, display_name, status, primary_contact_email, country_code) VALUES
    ('c1000000-0000-0000-0000-000000000001', 'ACME_CORP', 'Acme Corporation Ltd.', 'Acme Corp', 'ACTIVE', 'hr@acme.com', 'IN'),
    ('c1000000-0000-0000-0000-000000000002', 'TECHSTART', 'TechStart Pvt Ltd.', 'TechStart', 'ACTIVE', 'hr@techstart.com', 'IN');

-- Policy Accounts
INSERT INTO policy_account (policy_account_id, employer_id, insurer_id, policy_number, policy_type, policy_start_date, endorsement_account_ref, currency_code, endorsement_mode, status) VALUES
    ('d1000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000001', 'a1000000-0000-0000-0000-000000000001', 'POL-RT-2024-001', 'GROUP_HEALTH', '2024-01-01', 'EA-RT-ACME-001', 'INR', 'REALTIME', 'ACTIVE'),
    ('d1000000-0000-0000-0000-000000000002', 'c1000000-0000-0000-0000-000000000002', 'a1000000-0000-0000-0000-000000000002', 'POL-BATCH-2024-001', 'GROUP_HEALTH', '2024-01-01', 'EA-BATCH-TS-001', 'INR', 'BATCH', 'ACTIVE');

-- Policy Account Balances (starting balances)
INSERT INTO policy_account_balance (policy_account_id, confirmed_ea_balance, reserved_exposure, available_balance) VALUES
    ('d1000000-0000-0000-0000-000000000001', 50000000, 0, 50000000),  -- 5,00,000 INR (in paisa)
    ('d1000000-0000-0000-0000-000000000002', 30000000, 0, 30000000);  -- 3,00,000 INR (in paisa)

-- Alert configs
INSERT INTO alert_config (policy_account_id, alert_type, threshold_value, notification_email, is_active) VALUES
    ('d1000000-0000-0000-0000-000000000001', 'LOW_BALANCE', 5000000, 'hr@acme.com', TRUE),
    ('d1000000-0000-0000-0000-000000000002', 'LOW_BALANCE', 3000000, 'hr@techstart.com', TRUE);
