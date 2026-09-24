-- V24__employee_config_gross_salary_and_bonuses.sql
--
-- Adds current_gross_salary alongside the existing current_net_salary on
-- employee_payroll_config / employee_payroll_config_history, and a new
-- employee_payroll_bonus table for one-off "primes exceptionnelles" per employee.
-- Bonuses are tracked only — not fed into PayrollSimulatorService/TopologicalEvaluator.
-- See docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md.

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.employee_payroll_config') AND name = 'current_gross_salary'
)
BEGIN
    ALTER TABLE [dbo].[employee_payroll_config] ADD [current_gross_salary] NUMERIC(18,3) NULL;
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.employee_payroll_config_history') AND name = 'current_gross_salary'
)
BEGIN
    ALTER TABLE [dbo].[employee_payroll_config_history] ADD [current_gross_salary] NUMERIC(18,3) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'employee_payroll_bonus')
BEGIN
    CREATE TABLE [dbo].[employee_payroll_bonus] (
        [id]                BIGINT IDENTITY(1,1)  NOT NULL,
        [profile_user_id]   BIGINT                NOT NULL,
        [amount]            NUMERIC(18,3)         NOT NULL,
        [currency]          VARCHAR(3)            NOT NULL,
        [period_month]      TINYINT               NOT NULL,
        [period_year]       SMALLINT              NOT NULL,
        [label]             NVARCHAR(255)         NOT NULL,
        [comment]           NVARCHAR(1000)        NULL,
        [created_by]        BIGINT                NOT NULL,
        [created_at]        DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_employee_payroll_bonus] PRIMARY KEY ([id]),
        CONSTRAINT [CK_epb_period_month] CHECK ([period_month] BETWEEN 1 AND 12)
    );
    CREATE INDEX [IX_epb_profile_user_id] ON [dbo].[employee_payroll_bonus] ([profile_user_id]);
END
GO
