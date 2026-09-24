-- V3__parameter_sets.sql
-- Parameter sets, social charge rates, and benefits catalogue.

CREATE TABLE [dbo].[parameter_sets] (
    [id]                         BIGINT IDENTITY(1,1)  NOT NULL,
    [pays_id]                    BIGINT                NOT NULL,
    [version]                    INT                   NOT NULL,
    [fiscal_year]                INT                   NOT NULL,
    [status]                     NVARCHAR(255)         NOT NULL DEFAULT 'DRAFT',
    [irpp_brackets]              NVARCHAR(MAX)         NOT NULL,  -- JSON [{min,max,rate}]
    [convergence_tolerance]      NUMERIC(18,4)         NOT NULL DEFAULT 0.01,
    [max_convergence_iterations] INT                   NOT NULL DEFAULT 50,
    [calibration_threshold_pct]  NUMERIC(18,4)         NOT NULL DEFAULT 1.00,
    [approved_by_hr]             BIGINT                NULL,
    [approved_by_finance]        BIGINT                NULL,
    [approved_at]                DATETIMEOFFSET(6)     NULL,
    [activated_at]               DATETIMEOFFSET(6)     NULL,
    [change_rationale]           NVARCHAR(255)         NULL,
    [previous_version_id]        BIGINT                NULL,
    [created_by]                 BIGINT                NULL,
    [created_at]                 DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT [PK_parameter_sets]             PRIMARY KEY ([id]),
    CONSTRAINT [UX_parameter_sets_pays_ver]    UNIQUE ([pays_id], [version]),
    CONSTRAINT [CK_parameter_sets_status]      CHECK ([status] IN ('DRAFT','PENDING_FINANCE','ACTIVE','ARCHIVED')),
    CONSTRAINT [FK_parameter_sets_prev]        FOREIGN KEY ([previous_version_id]) REFERENCES [dbo].[parameter_sets]([id])
);
GO

CREATE TABLE [dbo].[social_charge_rates] (
    [id]                  BIGINT IDENTITY(1,1)  NOT NULL,
    [parameter_set_id]    BIGINT                NOT NULL,
    [contract_type]       NVARCHAR(255)         NOT NULL,  -- CDI|CDD|STAGE|CIVP
    [charge_code]         NVARCHAR(255)         NOT NULL,
    [charge_label]        NVARCHAR(255)         NOT NULL,
    [employee_rate]       NUMERIC(18,4)         NOT NULL DEFAULT 0.0000,
    [employer_rate]       NUMERIC(18,4)         NOT NULL DEFAULT 0.0000,
    [base_calculation]    NVARCHAR(255)         NOT NULL DEFAULT 'GROSS',  -- GROSS|CAPPED_GROSS|FIXED
    [cap_amount]          NUMERIC(18,4)         NULL,
    CONSTRAINT [PK_social_charge_rates]    PRIMARY KEY ([id]),
    CONSTRAINT [FK_scr_parameter_set]      FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[parameter_sets]([id]) ON DELETE CASCADE,
    CONSTRAINT [CK_scr_contract_type]      CHECK ([contract_type] IN ('CDI','CDD','STAGE','CIVP')),
    CONSTRAINT [CK_scr_base_calc]          CHECK ([base_calculation] IN ('GROSS','CAPPED_GROSS','FIXED'))
);
GO

CREATE TABLE [dbo].[benefits_catalogue] (
    [id]                  BIGINT IDENTITY(1,1)  NOT NULL,
    [parameter_set_id]    BIGINT                NOT NULL,
    [benefit_code]        NVARCHAR(255)         NOT NULL,  -- MEAL|TRANSPORT|HOUSING|SCHOOLING|OTHER
    [benefit_label_fr]    NVARCHAR(255)         NOT NULL,
    [benefit_label_en]    NVARCHAR(255)         NULL,
    [valuation_method]    NVARCHAR(255)         NOT NULL DEFAULT 'TAX_AUTHORITY',
    [monthly_value]       NUMERIC(18,4)         NOT NULL DEFAULT 0.0000,
    [employee_share]      NUMERIC(18,4)         NOT NULL DEFAULT 0.0000,
    [employer_share]      NUMERIC(18,4)         NOT NULL DEFAULT 0.0000,
    [is_taxable]          BIT                   NOT NULL DEFAULT 1,
    CONSTRAINT [PK_benefits_catalogue]     PRIMARY KEY ([id]),
    CONSTRAINT [FK_bc_parameter_set]       FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[parameter_sets]([id]) ON DELETE CASCADE,
    CONSTRAINT [CK_bc_benefit_code]        CHECK ([benefit_code] IN ('MEAL','TRANSPORT','HOUSING','SCHOOLING','OTHER')),
    CONSTRAINT [CK_bc_valuation]           CHECK ([valuation_method] IN ('TAX_AUTHORITY','ACTUAL_COST'))
);
GO
