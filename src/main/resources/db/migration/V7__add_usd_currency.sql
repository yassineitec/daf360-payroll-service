-- V7__add_usd_currency.sql
-- Replace CHF conversion columns with USD; add local_currency code per simulation row.
-- CHF columns (loaded_cost_chf, fx_rate_chf) are dropped — no production data existed.

ALTER TABLE [dbo].[simulation_results]
    ADD [local_currency] NVARCHAR(10)  NULL,
        [loaded_cost_usd] NUMERIC(18,4) NULL,
        [fx_rate_usd]     NUMERIC(18,4) NULL;
GO

ALTER TABLE [dbo].[simulation_results]
    DROP COLUMN [loaded_cost_chf],
                [fx_rate_chf];
GO
