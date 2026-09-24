-- V13__forex_snapshots.sql
-- Forex rate snapshots per payroll result (D3-253). Immutable: trigger blocks UPDATE/DELETE.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_forex_snapshots' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_forex_snapshots] (
        [id]                    BIGINT IDENTITY(1,1)    NOT NULL,
        [payroll_result_id]     BIGINT                  NOT NULL,
        [from_currency]         NVARCHAR(3)             NOT NULL,
        [to_currency]           NVARCHAR(3)             NOT NULL,
        [rate]                  NUMERIC(18,8)           NOT NULL,
        [source_name]           NVARCHAR(100)           NOT NULL,
        [source_http_code]      INT                     NOT NULL,
        [fetched_at]            DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_payroll_forex_snapshots]         PRIMARY KEY ([id]),
        CONSTRAINT [FK_forex_snap_result]               FOREIGN KEY ([payroll_result_id]) REFERENCES [dbo].[payroll_results]([id])
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_forex_snap_result' AND object_id = OBJECT_ID('dbo.payroll_forex_snapshots'))
    CREATE INDEX [IX_forex_snap_result] ON [dbo].[payroll_forex_snapshots] ([payroll_result_id]);
GO

-- Trigger: block ALL UPDATE and DELETE on payroll_forex_snapshots
CREATE OR ALTER TRIGGER [dbo].[trg_forex_snapshots_immutable]
ON [dbo].[payroll_forex_snapshots]
INSTEAD OF UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    RAISERROR(
        'payroll_forex_snapshots records are immutable. No UPDATE or DELETE is allowed.',
        16, 1
    );
END;
GO
