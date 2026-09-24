-- V11__drop_legacy_tables.sql
-- DEFERRED: payroll_rubriques_legacy and benefits_catalogue are still queried by
-- the existing simulation endpoint (IndividualSimulationService / ParameterSetService).
-- These will be dropped in a future migration once that endpoint is fully migrated
-- to the new universal PayrollOrchestrator engine.
DECLARE @noop BIT = 1; -- intentional no-op; do not remove or Flyway will skip checksum
GO
