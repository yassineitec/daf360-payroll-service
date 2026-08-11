-- V18__add_formula_expression_to_rubriques.sql
-- Enables manually-defined calculation formulas for payroll_rubriques_legacy.
-- Adds formula_expression (the expression string), display_order (evaluation
-- order for dependency resolution between formulas), and extends
-- CK_rubrique_calc_mode to allow the 'FORMULE' calc mode.
-- All blocks are idempotent.

-- ── 1. Add formula_expression column ──────────────────────────────────────────
IF OBJECT_ID('dbo.payroll_rubriques_legacy') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM sys.columns
       WHERE object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
         AND name = 'formula_expression'
   )
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    ADD [formula_expression] NVARCHAR(1000) NULL;
GO

-- ── 2. Add display_order column ────────────────────────────────────────────────
-- Controls evaluation order: a FORMULE rubrique with display_order=10 can
-- reference the result of a rubrique with display_order=5.
IF OBJECT_ID('dbo.payroll_rubriques_legacy') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM sys.columns
       WHERE object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
         AND name = 'display_order'
   )
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    ADD [display_order] INT NOT NULL DEFAULT 0;
GO

-- ── 3. Update CK_rubrique_calc_mode to include 'FORMULE' ─────────────────────
IF OBJECT_ID('dbo.payroll_rubriques_legacy') IS NOT NULL
   AND EXISTS (
       SELECT 1 FROM sys.check_constraints
       WHERE name = 'CK_rubrique_calc_mode'
         AND parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
   )
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    DROP CONSTRAINT [CK_rubrique_calc_mode];
GO

IF OBJECT_ID('dbo.payroll_rubriques_legacy') IS NOT NULL
   AND NOT EXISTS (
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
            'POURCENTAGE_PLAFONNE',
            'FORMULE'
        ));
GO
