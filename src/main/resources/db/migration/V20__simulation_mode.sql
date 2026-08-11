-- =============================================================================
-- V20: Add simulation direction mode to simulation_results
--      NET_TO_BRUT (default) = classic convergence (net → gross)
--      BRUT_TO_NET           = single-pass top-down (gross → net)
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID(N'[dbo].[simulation_results]')
      AND name = N'mode'
)
BEGIN
    ALTER TABLE [dbo].[simulation_results]
        ADD [mode] NVARCHAR(20) NOT NULL
            CONSTRAINT [DF_SimResult_Mode] DEFAULT N'NET_TO_BRUT';
END
GO
