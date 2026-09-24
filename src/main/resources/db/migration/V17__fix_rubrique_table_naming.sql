-- V17__fix_rubrique_table_naming.sql
-- V9 was supposed to rename payroll_rubriques (V8 simulation schema) →
-- payroll_rubriques_legacy, but V8 hadn't been written yet when V9 first ran
-- on this DB (installed_rank 9 vs 2). The rename was silently skipped.
-- This migration repairs the state: renames the V8-schema table to _legacy,
-- creates the V9 universal-engine payroll_rubriques, then applies the
-- cap_amount column and 5-value constraint that V16 could not add
-- (V16 ran as a no-op on this DB because the table was absent).
-- All blocks are idempotent.

-- ── 1. Rename simulation table (V8 schema) to legacy ──────────────────────────
-- Only rename when: source exists, dest doesn't, and source has parameter_set_id
-- (confirms it's the V8-schema table, not the V9 universal-engine table).
IF OBJECT_ID('dbo.payroll_rubriques', 'U') IS NOT NULL
   AND OBJECT_ID('dbo.payroll_rubriques_legacy', 'U') IS NULL
   AND EXISTS (
       SELECT 1 FROM sys.columns
       WHERE object_id = OBJECT_ID('dbo.payroll_rubriques')
         AND name = 'parameter_set_id'
   )
    EXEC sp_rename '[dbo].[payroll_rubriques]', 'payroll_rubriques_legacy';
GO

-- ── 2. Rename PK on legacy table to free the canonical constraint name ─────────
-- Needed so the new payroll_rubriques table can reuse PK_payroll_rubriques.
IF EXISTS (
    SELECT 1 FROM sys.key_constraints kc
    WHERE kc.name = 'PK_payroll_rubriques'
      AND kc.parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
)
    EXEC sp_rename N'[dbo].[PK_payroll_rubriques]', N'PK_payroll_rubriques_legacy', N'OBJECT';
GO

-- ── 3. Create universal-engine payroll_rubriques (D3-236, V9 schema) ──────────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_rubriques' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_rubriques] (
        [id]                    BIGINT IDENTITY(1,1)    NOT NULL,
        [country_id]            BIGINT                  NOT NULL,
        [code]                  NVARCHAR(20)            NOT NULL,
        [label_fr]              NVARCHAR(200)           NOT NULL,
        [label_en]              NVARCHAR(200)           NULL,
        [strate]                INT                     NOT NULL,
        [nature]                NVARCHAR(20)            NOT NULL,
        [mode_calcul]           NVARCHAR(30)            NOT NULL,
        [assiette_code]         NVARCHAR(20)            NULL,
        [param_key_taux]        NVARCHAR(50)            NULL,
        [param_key_plafond]     NVARCHAR(50)            NULL,
        [param_key_bareme]      NVARCHAR(50)            NULL,
        [formula_expression]    NVARCHAR(1000)          NULL,
        [contract_type_filter]  NVARCHAR(200)           NULL,
        [periodicite]           NVARCHAR(20)            NOT NULL DEFAULT 'MENSUEL',
        [prorata_applicable]    BIT                     NOT NULL DEFAULT 0,
        [display_order]         INT                     NOT NULL DEFAULT 0,
        [active]                BIT                     NOT NULL DEFAULT 1,
        CONSTRAINT [PK_payroll_rubriques]       PRIMARY KEY ([id]),
        CONSTRAINT [FK_rubrique_country]        FOREIGN KEY ([country_id]) REFERENCES [dbo].[payroll_countries]([id]),
        CONSTRAINT [UX_rubrique_country_code]   UNIQUE ([country_id], [code]),
        CONSTRAINT [CK_rubrique_strate]         CHECK ([strate] BETWEEN 1 AND 5),
        CONSTRAINT [CK_rubrique_nature]         CHECK ([nature]     IN ('GAIN','RETENUE','COTISATION','TAXE','AVANTAGE')),
        CONSTRAINT [CK_rubrique_mode_calcul]    CHECK ([mode_calcul] IN ('TAUX_PCT','MONTANT_FIXE','BAREME_PROGRESSIF','ANNUALISE_BAREME','FORMULE','NEWTON_RAPHSON')),
        CONSTRAINT [CK_rubrique_periodicite]    CHECK ([periodicite]  IN ('MENSUEL','TRIMESTRIEL','ANNUEL'))
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_rubrique_country_active' AND object_id = OBJECT_ID('dbo.payroll_rubriques'))
    CREATE INDEX [IX_rubrique_country_active] ON [dbo].[payroll_rubriques] ([country_id], [active]);
GO

-- ── 4. Add cap_amount to payroll_rubriques_legacy ─────────────────────────────
-- V16 was a no-op on this DB (table didn't exist when V16 ran).
IF OBJECT_ID('dbo.payroll_rubriques_legacy') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM sys.columns
       WHERE object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
         AND name = 'cap_amount'
   )
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    ADD [cap_amount] NUMERIC(18, 4) NULL;
GO

-- ── 5. Update CK_rubrique_calc_mode to allow POURCENTAGE_PLAFONNE ─────────────
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
            'POURCENTAGE_PLAFONNE'
        ));
GO
