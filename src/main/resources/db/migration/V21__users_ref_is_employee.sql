-- =============================================================================
-- V21__users_ref_is_employee.sql
--
-- Carries the RH module's binary is_employee flag into this service's users_ref
-- shadow table, so the pickers here stop offering test and machine accounts.
--
-- The flag travels rather than the rows being filtered upstream:
-- /api/hr/users-for-sync mirrors ACCOUNTS on purpose, because tables in the
-- consuming services reference users_ref and a missing row could orphan a record.
-- Presence is a technical concern, listing is a business one.
--
-- Default 1, so the column is harmless until the next sync writes real values.
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'users_ref' AND COLUMN_NAME = 'is_employee'
)
BEGIN
    ALTER TABLE [dbo].[users_ref]
        ADD [is_employee] BIT NOT NULL
            CONSTRAINT [DF_payroll_users_ref_is_employee] DEFAULT (1);
END
