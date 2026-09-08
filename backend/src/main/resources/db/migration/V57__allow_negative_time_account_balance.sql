-- Allow imported cutover opening balances to be negative (overused leave).
-- Display continues to use hours / 8 as days. Ledger amounts remain non-zero.

ALTER TABLE time_account
    DROP CHECK ck_time_account_balance;
