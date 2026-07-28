-- V6__indexes.sql
-- Performance indexes on high-frequency query columns.

CREATE INDEX [IX_parameter_sets_pays_status]
    ON [dbo].[parameter_sets] ([pays_id], [status]);
GO

CREATE INDEX [IX_parameter_sets_pays_year]
    ON [dbo].[parameter_sets] ([pays_id], [fiscal_year]);
GO

CREATE INDEX [IX_simulation_results_pays]
    ON [dbo].[simulation_results] ([pays_id], [simulated_at] DESC);
GO

CREATE INDEX [IX_simulation_results_cohort]
    ON [dbo].[simulation_results] ([cohort_id])
    WHERE [cohort_id] IS NOT NULL;
GO

CREATE INDEX [IX_calibration_cycles_pays]
    ON [dbo].[calibration_cycles] ([pays_id], [period] DESC);
GO

CREATE INDEX [IX_calibration_variances_cycle]
    ON [dbo].[calibration_variances] ([cycle_id]);
GO

CREATE INDEX [IX_social_charge_rates_param]
    ON [dbo].[social_charge_rates] ([parameter_set_id]);
GO

CREATE INDEX [IX_benefits_catalogue_param]
    ON [dbo].[benefits_catalogue] ([parameter_set_id]);
GO
