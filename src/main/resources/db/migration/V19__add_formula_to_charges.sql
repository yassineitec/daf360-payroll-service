-- V19__add_formula_to_charges.sql
-- Extends social_charge_rates with formula support for the unified topological
-- evaluation engine (DAF360° H.10 long-term solution).
--
-- formula_ee  — arithmetic expression for the employee-side charge amount.
--               When set, overrides the standard employee_rate × base computation.
--               Variables: BRUT, {PRIOR_CHARGE_CODE}_EE, {PRIOR_CHARGE_CODE}_ER.
-- formula_er  — same for the employer-side charge amount.
-- eval_order  — evaluation order within a parameter set; lower = evaluated first.
--               A formula charge with eval_order=20 can reference the results of
--               charges with eval_order=10.
-- All blocks are idempotent.

-- ── 1. formula_ee column ─────────────────────────────────────────────────────
IF OBJECT_ID('dbo.social_charge_rates') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM sys.columns
       WHERE object_id = OBJECT_ID('dbo.social_charge_rates')
         AND name = 'formula_ee'
   )
    ALTER TABLE [dbo].[social_charge_rates]
    ADD [formula_ee] NVARCHAR(1000) NULL;
GO

-- ── 2. formula_er column ─────────────────────────────────────────────────────
IF OBJECT_ID('dbo.social_charge_rates') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM sys.columns
       WHERE object_id = OBJECT_ID('dbo.social_charge_rates')
         AND name = 'formula_er'
   )
    ALTER TABLE [dbo].[social_charge_rates]
    ADD [formula_er] NVARCHAR(1000) NULL;
GO

-- ── 3. eval_order column ──────────────────────────────────────────────────────
IF OBJECT_ID('dbo.social_charge_rates') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM sys.columns
       WHERE object_id = OBJECT_ID('dbo.social_charge_rates')
         AND name = 'eval_order'
   )
    ALTER TABLE [dbo].[social_charge_rates]
    ADD [eval_order] INT NOT NULL DEFAULT 0;
GO
