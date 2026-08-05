-- V16__add_pourcentage_plafonne.sql
-- Extends payroll_rubriques_legacy (the V8-schema simulation table) to support
-- POURCENTAGE_PLAFONNE calc mode: amount = min(gross, cap_amount) × rate.
-- Each block is idempotent for safe re-run.

-- ── 1. Drop old calc_mode constraint (4 values) ───────────────────────────────
IF EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'CK_rubrique_calc_mode'
      AND parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
)
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    DROP CONSTRAINT [CK_rubrique_calc_mode];
GO

-- ── 2. Re-add with 5 values ───────────────────────────────────────────────────
IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'CK_rubrique_calc_mode'
      AND parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
)
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    ADD CONSTRAINT [CK_rubrique_calc_mode]
        CHECK ([calc_mode] IN (
            'FIXE_MENSUEL',
            'FIXE_JOURNALIER',
            'POURCENTAGE_BRUT',
            'POURCENTAGE_CHARGES',
            'POURCENTAGE_PLAFONNE'
        ));
GO

-- ── 3. Add cap_amount column ───────────────────────────────────────────────────
-- Used by POURCENTAGE_PLAFONNE: effective base = min(gross, cap_amount).
-- NULL means no cap (behaviour identical to POURCENTAGE_BRUT).
IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
      AND name = 'cap_amount'
)
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    ADD [cap_amount] NUMERIC(18, 4) NULL;
GO
