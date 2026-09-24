-- V8__payroll_rubriques.sql
-- Rubriques de Paie module: flexible payroll items (meal vouchers, primes, indemnities, deductions)
-- Supersedes benefits_catalogue for new parameter sets; old data kept for backward compatibility.

CREATE TABLE [dbo].[payroll_rubriques] (
    [id]                           BIGINT IDENTITY(1,1)  NOT NULL,
    [parameter_set_id]             BIGINT                NOT NULL,
    [code]                         NVARCHAR(50)          NOT NULL,
    [label_fr]                     NVARCHAR(200)         NOT NULL,
    [label_en]                     NVARCHAR(200)         NULL,
    [nature]                       NVARCHAR(20)          NOT NULL,
    [calc_mode]                    NVARCHAR(30)          NOT NULL,
    [amount]                       NUMERIC(18,4)         NULL,
    [rate]                         NUMERIC(10,6)         NULL,
    [employer_share_pct]           NUMERIC(10,6)         NOT NULL DEFAULT 0.000000,
    [employee_share_pct]           NUMERIC(10,6)         NOT NULL DEFAULT 0.000000,
    [is_subject_to_social_charges] BIT                   NOT NULL DEFAULT 0,
    [is_subject_to_irpp]           BIT                   NOT NULL DEFAULT 1,
    [direction]                    NVARCHAR(10)          NOT NULL DEFAULT 'CREDIT',
    [contract_types]               NVARCHAR(100)         NULL,
    [is_active]                    BIT                   NOT NULL DEFAULT 1,
    [created_at]                   DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT [PK_payroll_rubriques]       PRIMARY KEY ([id]),
    CONSTRAINT [FK_rubrique_param_set]      FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[parameter_sets]([id]) ON DELETE CASCADE,
    CONSTRAINT [UX_rubrique_ps_code]        UNIQUE ([parameter_set_id], [code]),
    CONSTRAINT [CK_rubrique_nature]         CHECK ([nature]    IN ('AVANTAGE','INDEMNITE','PRIME','RETENUE')),
    CONSTRAINT [CK_rubrique_calc_mode]      CHECK ([calc_mode] IN ('FIXE_MENSUEL','FIXE_JOURNALIER','POURCENTAGE_BRUT','POURCENTAGE_CHARGES')),
    CONSTRAINT [CK_rubrique_direction]      CHECK ([direction] IN ('CREDIT','DEBIT'))
);
GO

CREATE INDEX [IX_rubrique_parameter_set] ON [dbo].[payroll_rubriques] ([parameter_set_id]);
GO

ALTER TABLE [dbo].[simulation_results]
    ADD [rubriques_applied] NVARCHAR(MAX) NULL;
GO
