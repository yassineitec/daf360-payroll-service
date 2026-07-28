-- V4__simulation.sql
-- Simulation results and cohort definitions.

CREATE TABLE [dbo].[cohort_definitions] (
    [id]                  BIGINT IDENTITY(1,1)  NOT NULL,
    [pays_id]             BIGINT                NOT NULL,
    [name]                NVARCHAR(255)         NOT NULL,
    [fiscal_year]         INT                   NOT NULL,
    [parameter_set_id]    BIGINT                NOT NULL,
    [status]              NVARCHAR(255)         NOT NULL DEFAULT 'DRAFT',
    [total_loaded_cost]   NUMERIC(18,4)         NULL,
    [total_headcount]     INT                   NULL,
    [created_by]          BIGINT                NULL,
    [created_at]          DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT [PK_cohort_definitions]    PRIMARY KEY ([id]),
    CONSTRAINT [CK_cohort_status]         CHECK ([status] IN ('DRAFT','VALIDATED','ARCHIVED')),
    CONSTRAINT [FK_cohort_param_set]      FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[parameter_sets]([id])
);
GO

CREATE TABLE [dbo].[simulation_results] (
    [id]                  BIGINT IDENTITY(1,1)  NOT NULL,
    [pays_id]             BIGINT                NOT NULL,
    [profile_user_id]     BIGINT                NULL,       -- NULL for ad-hoc simulations
    [parameter_set_id]    BIGINT                NOT NULL,
    [simulation_type]     NVARCHAR(255)         NOT NULL,   -- INDIVIDUAL|COHORT
    [contract_type]       NVARCHAR(255)         NOT NULL DEFAULT 'CDI',
    [input_net]           NUMERIC(18,4)         NOT NULL,
    [net_taxable]         NUMERIC(18,4)         NOT NULL,
    [taxable_base]        NUMERIC(18,4)         NOT NULL,
    [gross]               NUMERIC(18,4)         NOT NULL,
    [loaded_cost]         NUMERIC(18,4)         NOT NULL,
    [loaded_cost_eur]     NUMERIC(18,4)         NULL,
    [loaded_cost_chf]     NUMERIC(18,4)         NULL,
    [fx_rate_eur]         NUMERIC(18,4)         NULL,
    [fx_rate_chf]         NUMERIC(18,4)         NULL,
    [irpp_amount]         NUMERIC(18,4)         NULL,
    [employee_charges]    NUMERIC(18,4)         NULL,
    [employer_charges]    NUMERIC(18,4)         NULL,
    [benefits_applied]    NVARCHAR(255)         NULL,       -- JSON [{code, value}]
    [iterations_used]     INT                   NOT NULL DEFAULT 0,
    [convergence_ok]      BIT                   NOT NULL DEFAULT 1,
    [cohort_id]           BIGINT                NULL,
    [simulated_by]        BIGINT                NOT NULL,
    [simulated_at]        DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT [PK_simulation_results]    PRIMARY KEY ([id]),
    CONSTRAINT [CK_sim_type]              CHECK ([simulation_type] IN ('INDIVIDUAL','COHORT')),
    CONSTRAINT [FK_sim_param_set]         FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[parameter_sets]([id]),
    CONSTRAINT [FK_sim_cohort]            FOREIGN KEY ([cohort_id]) REFERENCES [dbo].[cohort_definitions]([id])
);
GO
