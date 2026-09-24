-- V14: Add CHF currency support, S4 (gross_with_benefits), cost/net ratio,
--      and candidate metadata to simulation_results for the 5-strata display.

ALTER TABLE [dbo].[simulation_results]
    ADD [loaded_cost_chf]      DECIMAL(18,4) NULL,
        [fx_rate_chf]          DECIMAL(18,6) NULL,
        [gross_with_benefits]  DECIMAL(18,4) NULL,   -- S4 = gross + avantages exonérés
        [cost_net_ratio]       DECIMAL(18,6) NULL,   -- loadedCost / inputNet
        [candidate_label]      NVARCHAR(200)  NULL,  -- free-text label for PDF
        [poste]                NVARCHAR(200)  NULL,
        [grade]                NVARCHAR(100)  NULL,
        [discipline]           NVARCHAR(100)  NULL;
GO
