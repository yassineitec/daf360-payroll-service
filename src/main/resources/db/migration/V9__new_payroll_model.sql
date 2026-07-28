-- V9__new_payroll_model.sql
-- Universal payroll engine: D3-235 payroll_countries, D3-236 new payroll_rubriques,
-- D3-237 payroll_parameter_sets, D3-238 payroll_calculation_sequences, D3-239 payroll_results.
-- Old payroll_rubriques (V8) renamed to payroll_rubriques_legacy for safety; dropped in V11.
-- Each block is idempotent to handle partial-run recovery.

-- ─── Step 1: Rename V8 payroll_rubriques to legacy ───────────────────────────
IF OBJECT_ID('dbo.payroll_rubriques', 'U') IS NOT NULL
   AND OBJECT_ID('dbo.payroll_rubriques_legacy', 'U') IS NULL
    EXEC sp_rename '[dbo].[payroll_rubriques]', 'payroll_rubriques_legacy';
GO

-- Rename PK on legacy table so the new engine table can reuse the canonical name.
IF EXISTS (
    SELECT 1 FROM sys.key_constraints kc
    WHERE kc.name = 'PK_payroll_rubriques'
      AND kc.parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
)
    EXEC sp_rename N'[dbo].[PK_payroll_rubriques]', N'PK_payroll_rubriques_legacy', N'OBJECT';
GO

-- Rename CK_rubrique_nature on legacy table so the new engine table can reuse it.
IF EXISTS (
    SELECT 1 FROM sys.check_constraints cc
    WHERE cc.name = 'CK_rubrique_nature'
      AND cc.parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
)
    EXEC sp_rename N'[dbo].[CK_rubrique_nature]', N'CK_rubrique_nature_legacy', N'OBJECT';
GO

-- ─── D3-235: payroll_countries ────────────────────────────────────────────────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_countries' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_countries] (
        [id]                        BIGINT IDENTITY(1,1)    NOT NULL,
        [pays_id]                   BIGINT                  NOT NULL,
        [currency_code]             NVARCHAR(3)             NOT NULL,
        [fiscal_year_start_month]   INT                     NOT NULL DEFAULT 1,
        [forex_api_sources]         NVARCHAR(MAX)           NULL,   -- JSON [{currency, url, apiKey}]
        [active]                    BIT                     NOT NULL DEFAULT 0,
        [created_at]                DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_payroll_countries]           PRIMARY KEY ([id]),
        CONSTRAINT [UX_payroll_countries_pays]      UNIQUE ([pays_id]),
        CONSTRAINT [CK_payroll_countries_month]     CHECK ([fiscal_year_start_month] BETWEEN 1 AND 12)
    );
END
GO

-- ─── D3-236: payroll_rubriques (structural definition, no numeric values) ─────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_rubriques' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_rubriques] (
        [id]                    BIGINT IDENTITY(1,1)    NOT NULL,
        [country_id]            BIGINT                  NOT NULL,
        [code]                  NVARCHAR(20)            NOT NULL,
        [label_fr]              NVARCHAR(200)           NOT NULL,
        [label_en]              NVARCHAR(200)           NULL,
        [strate]                INT                     NOT NULL,   -- 1=brut 2=avantages 3=charges 4=net-imposable 5=net-à-payer
        [nature]                NVARCHAR(20)            NOT NULL,
        [mode_calcul]           NVARCHAR(30)            NOT NULL,
        [assiette_code]         NVARCHAR(20)            NULL,       -- variable name in context (e.g. BRUT, STRATE_1)
        [param_key_taux]        NVARCHAR(50)            NULL,
        [param_key_plafond]     NVARCHAR(50)            NULL,
        [param_key_bareme]      NVARCHAR(50)            NULL,
        [formula_expression]    NVARCHAR(1000)          NULL,
        [contract_type_filter]  NVARCHAR(200)           NULL,       -- null = all; "CDI,CDD" = specific
        [periodicite]           NVARCHAR(20)            NOT NULL DEFAULT 'MENSUEL',
        [prorata_applicable]    BIT                     NOT NULL DEFAULT 0,
        [display_order]         INT                     NOT NULL DEFAULT 0,
        [active]                BIT                     NOT NULL DEFAULT 1,
        CONSTRAINT [PK_payroll_rubriques]               PRIMARY KEY ([id]),
        CONSTRAINT [FK_rubrique_country]                FOREIGN KEY ([country_id]) REFERENCES [dbo].[payroll_countries]([id]),
        CONSTRAINT [UX_rubrique_country_code]           UNIQUE ([country_id], [code]),
        CONSTRAINT [CK_rubrique_strate]                 CHECK ([strate] BETWEEN 1 AND 5),
        CONSTRAINT [CK_rubrique_nature]                 CHECK ([nature]      IN ('GAIN','RETENUE','COTISATION','TAXE','AVANTAGE')),
        CONSTRAINT [CK_rubrique_mode_calcul]            CHECK ([mode_calcul] IN ('TAUX_PCT','MONTANT_FIXE','BAREME_PROGRESSIF','ANNUALISE_BAREME','FORMULE','NEWTON_RAPHSON')),
        CONSTRAINT [CK_rubrique_periodicite]            CHECK ([periodicite]  IN ('MENSUEL','TRIMESTRIEL','ANNUEL'))
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_rubrique_country_active' AND object_id = OBJECT_ID('dbo.payroll_rubriques'))
    CREATE INDEX [IX_rubrique_country_active] ON [dbo].[payroll_rubriques] ([country_id], [active]);
GO

-- ─── D3-237: payroll_parameter_sets ──────────────────────────────────────────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_parameter_sets' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_parameter_sets] (
        [id]                    BIGINT IDENTITY(1,1)    NOT NULL,
        [country_id]            BIGINT                  NOT NULL,
        [version_number]        INT                     NOT NULL,
        [status]                NVARCHAR(20)            NOT NULL DEFAULT 'DRAFT',
        [effective_date]        DATE                    NOT NULL,
        [parameters]            NVARCHAR(MAX)           NOT NULL,   -- JSON: taux, barèmes, plafonds, seuils
        [submitted_by]          NVARCHAR(100)           NULL,
        [submitted_at]          DATETIMEOFFSET(6)       NULL,
        [approved_by_hr]        NVARCHAR(100)           NULL,
        [approved_at_hr]        DATETIMEOFFSET(6)       NULL,
        [approved_by_finance]   NVARCHAR(100)           NULL,
        [approved_at_finance]   DATETIMEOFFSET(6)       NULL,
        [activated_at]          DATETIMEOFFSET(6)       NULL,
        [archived_at]           DATETIMEOFFSET(6)       NULL,
        [created_by]            NVARCHAR(100)           NULL,
        [created_at]            DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_payroll_parameter_sets]          PRIMARY KEY ([id]),
        CONSTRAINT [FK_param_set_country]               FOREIGN KEY ([country_id]) REFERENCES [dbo].[payroll_countries]([id]),
        CONSTRAINT [UX_param_set_country_version]       UNIQUE ([country_id], [version_number]),
        CONSTRAINT [CK_param_set_status]                CHECK ([status] IN ('DRAFT','SUBMITTED','APPROVED_HR','APPROVED_FINANCE','ACTIVE','ARCHIVED'))
    );
END
GO

-- ─── D3-238: payroll_calculation_sequences ────────────────────────────────────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_calculation_sequences' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_calculation_sequences] (
        [id]                    BIGINT IDENTITY(1,1)    NOT NULL,
        [country_id]            BIGINT                  NOT NULL,
        [parameter_set_id]      BIGINT                  NULL,
        [steps]                 NVARCHAR(MAX)           NOT NULL,   -- JSON: [{stepOrder, rubriqueCodes[], outputVariable}]
        [active]                BIT                     NOT NULL DEFAULT 0,
        CONSTRAINT [PK_payroll_calc_sequences]          PRIMARY KEY ([id]),
        CONSTRAINT [FK_calc_seq_country]                FOREIGN KEY ([country_id]) REFERENCES [dbo].[payroll_countries]([id]),
        CONSTRAINT [FK_calc_seq_param_set]              FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[payroll_parameter_sets]([id])
    );
END
GO

-- ─── D3-239: payroll_results (immutable; trigger added in V10) ────────────────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_results' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_results] (
        [id]                        BIGINT IDENTITY(1,1)    NOT NULL,
        [employee_id]               BIGINT                  NOT NULL,
        [country_id]                BIGINT                  NOT NULL,
        [pays_id]                   BIGINT                  NOT NULL,
        [period_year]               INT                     NOT NULL,
        [period_month]              INT                     NOT NULL,
        [parameter_set_id]          BIGINT                  NOT NULL,
        [calculated_at]             DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [triggered_by]              NVARCHAR(100)           NULL,
        [rubrique_details]          NVARCHAR(MAX)           NOT NULL,   -- JSON array of per-rubrique results
        [strate_1]                  NUMERIC(15,2)           NULL,       -- Salaire brut
        [strate_2]                  NUMERIC(15,2)           NULL,       -- Avantages
        [strate_3]                  NUMERIC(15,2)           NULL,       -- Charges salariales
        [strate_4]                  NUMERIC(15,2)           NULL,       -- Net imposable
        [strate_5]                  NUMERIC(15,2)           NULL,       -- Net à payer
        [aggregate_gross]           NUMERIC(15,2)           NULL,
        [aggregate_employer_charges] NUMERIC(15,2)          NULL,
        [aggregate_net]             NUMERIC(15,2)           NULL,
        [aggregate_irpp]            NUMERIC(15,2)           NULL,
        [loaded_cost]               NUMERIC(15,2)           NULL,
        [forex_snapshot_json]       NVARCHAR(MAX)           NULL,
        [convergence_ok]            BIT                     NOT NULL DEFAULT 1,
        [iterations_used]           INT                     NULL,
        CONSTRAINT [PK_payroll_results]                 PRIMARY KEY ([id]),
        CONSTRAINT [FK_result_country]                  FOREIGN KEY ([country_id]) REFERENCES [dbo].[payroll_countries]([id]),
        CONSTRAINT [FK_result_param_set]                FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[payroll_parameter_sets]([id]),
        CONSTRAINT [CK_result_period_month]             CHECK ([period_month] BETWEEN 1 AND 12)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_payroll_results_employee_period' AND object_id = OBJECT_ID('dbo.payroll_results'))
    CREATE INDEX [IX_payroll_results_employee_period] ON [dbo].[payroll_results] ([employee_id], [period_year], [period_month]);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_payroll_results_country_period' AND object_id = OBJECT_ID('dbo.payroll_results'))
    CREATE INDEX [IX_payroll_results_country_period]  ON [dbo].[payroll_results] ([country_id],  [period_year], [period_month]);
GO
