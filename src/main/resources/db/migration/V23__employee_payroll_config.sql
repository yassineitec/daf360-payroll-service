-- V23__employee_payroll_config.sql
--
-- Per-employee payroll configuration (country, contract type, selected benefits-in-kind,
-- current net salary), the first per-EMPLOYEE scoping anywhere in this schema — every
-- existing parameter_sets/payroll_parameter_sets row is scoped to pays_id only. Mirrors
-- daf360-rh-service's Working-Time-Regime pattern: one mutable "current" row plus an
-- append-only history table with a required reason, since this changes real pay.
--
-- current_net_salary starts NULL for every employee (no bulk backfill from RH's existing
-- salaireNetRh — see docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md §6)
-- and is populated one employee at a time, from either app, going forward.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'employee_payroll_config')
BEGIN
    CREATE TABLE [dbo].[employee_payroll_config] (
        [id]                     BIGINT IDENTITY(1,1)  NOT NULL,
        [profile_user_id]        BIGINT                NOT NULL,
        [pays_id]                BIGINT                NOT NULL,
        [contract_type]          NVARCHAR(255)         NOT NULL,  -- CDI|CDD|CIVP|STAGE|FREELANCE|DETACHEMENT
                                                                    -- (daf360-rh-service's real domain — see
                                                                    -- EmployeeContract.contractTypeCode / ContractTypeConfig.contractTypeCode —
                                                                    -- not SimulationRequest's narrower 4-value comment)
        [selected_benefit_codes] NVARCHAR(MAX)         NULL,      -- JSON array of codes, null = none selected
        [current_net_salary]     NUMERIC(18,3)         NULL,
        [updated_by]             BIGINT                NOT NULL,
        [updated_at]             DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [created_at]             DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_employee_payroll_config]     PRIMARY KEY ([id]),
        CONSTRAINT [UX_epc_profile_user_id]         UNIQUE ([profile_user_id]),
        CONSTRAINT [CK_epc_contract_type]           CHECK ([contract_type] IN ('CDI','CDD','CIVP','STAGE','FREELANCE','DETACHEMENT'))
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'employee_payroll_config_history')
BEGIN
    CREATE TABLE [dbo].[employee_payroll_config_history] (
        [id]                     BIGINT IDENTITY(1,1)  NOT NULL,
        [profile_user_id]        BIGINT                NOT NULL,
        [pays_id]                BIGINT                NOT NULL,
        [contract_type]          NVARCHAR(255)         NOT NULL,
        [selected_benefit_codes] NVARCHAR(MAX)         NULL,
        [current_net_salary]     NUMERIC(18,3)         NULL,
        [reason]                 NVARCHAR(500)         NOT NULL,
        [changed_by]             BIGINT                NOT NULL,
        [changed_at]             DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_employee_payroll_config_history] PRIMARY KEY ([id])
    );
    CREATE INDEX [IX_epch_profile_user_id] ON [dbo].[employee_payroll_config_history] ([profile_user_id]);
END
GO
