-- V10__immutability_triggers.sql
-- Immutability constraints via DB triggers (D3-239 payroll_results, D3-237 ACTIVE param sets).

-- ─── Trigger: block ALL UPDATE and DELETE on payroll_results ──────────────────
CREATE OR ALTER TRIGGER [dbo].[trg_payroll_results_immutable]
ON [dbo].[payroll_results]
INSTEAD OF UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    RAISERROR(
        'payroll_results records are immutable. No UPDATE or DELETE is allowed after insertion.',
        16, 1
    );
END;
GO

-- ─── Trigger: block modifications to ACTIVE payroll_parameter_sets ───────────
-- Only ACTIVE → ARCHIVED is permitted; any other UPDATE to an ACTIVE row is rejected.
CREATE OR ALTER TRIGGER [dbo].[trg_param_set_active_lock]
ON [dbo].[payroll_parameter_sets]
AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF EXISTS (
        SELECT 1
        FROM   deleted d
        INNER JOIN inserted i ON d.id = i.id
        WHERE  d.status = 'ACTIVE'
          AND  i.status != 'ARCHIVED'
    )
    BEGIN
        RAISERROR(
            'Cannot modify an ACTIVE payroll_parameter_set. Only the ACTIVE→ARCHIVED transition is allowed.',
            16, 1
        );
        ROLLBACK TRANSACTION;
    END;
END;
GO
