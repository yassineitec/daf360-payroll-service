-- V12__calibration_new.sql
-- New calibration tables: D3-256 import tracking + variance lines, D3-258 KPI precision history.
-- All CREATE TABLE statements are wrapped in IF NOT EXISTS for idempotency.

-- ─── D3-256: payroll_calibration_imports ─────────────────────────────────────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_calibration_imports' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_calibration_imports] (
        [id]                    BIGINT IDENTITY(1,1)    NOT NULL,
        [country_id]            BIGINT                  NOT NULL,
        [period]                NVARCHAR(7)             NOT NULL,   -- YYYY-MM
        [import_status]         NVARCHAR(20)            NOT NULL DEFAULT 'PENDING',
        [imported_at]           DATETIMEOFFSET(6)       NULL,
        [imported_by]           NVARCHAR(100)           NULL,
        [file_name]             NVARCHAR(500)           NULL,
        [global_precision_pct]  NUMERIC(10,4)           NULL,
        [parameter_set_id]      BIGINT                  NULL,
        [triggered_at]          DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [deadline_j5]           DATETIMEOFFSET(6)       NULL,
        CONSTRAINT [PK_calibration_imports]             PRIMARY KEY ([id]),
        CONSTRAINT [FK_cal_import_country]              FOREIGN KEY ([country_id])      REFERENCES [dbo].[payroll_countries]([id]),
        CONSTRAINT [FK_cal_import_param_set]            FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[payroll_parameter_sets]([id]),
        CONSTRAINT [UX_cal_import_country_period]       UNIQUE ([country_id], [period]),
        CONSTRAINT [CK_cal_import_status]               CHECK ([import_status] IN ('PENDING','IMPORTED','COMPARISON_DONE','REQUIRES_UPDATE','CLOSED'))
    );
END
GO

-- ─── D3-256: payroll_calibration_lines (per-rubrique variance) ───────────────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_calibration_lines' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_calibration_lines] (
        [id]                    BIGINT IDENTITY(1,1)    NOT NULL,
        [import_id]             BIGINT                  NOT NULL,
        [rubrique_code]         NVARCHAR(20)            NOT NULL,
        [predicted_amount]      NUMERIC(15,2)           NOT NULL,
        [actual_amount]         NUMERIC(15,2)           NOT NULL,
        [variance_amount]       NUMERIC(15,2)           NOT NULL,
        [variance_pct]          NUMERIC(10,4)           NOT NULL,
        [exceeds_threshold]     BIT                     NOT NULL DEFAULT 0,
        [created_at]            DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_calibration_lines]               PRIMARY KEY ([id]),
        CONSTRAINT [FK_cal_line_import]                 FOREIGN KEY ([import_id]) REFERENCES [dbo].[payroll_calibration_imports]([id]) ON DELETE CASCADE
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_cal_line_import' AND object_id = OBJECT_ID('dbo.payroll_calibration_lines'))
    CREATE INDEX [IX_cal_line_import] ON [dbo].[payroll_calibration_lines] ([import_id]);
GO

-- ─── D3-258: payroll_precision_kpi_history ────────────────────────────────────
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'payroll_precision_kpi_history' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE [dbo].[payroll_precision_kpi_history] (
        [id]                        BIGINT IDENTITY(1,1)    NOT NULL,
        [country_id]                BIGINT                  NOT NULL,
        [period]                    NVARCHAR(7)             NOT NULL,   -- YYYY-MM
        [precision_pct]             NUMERIC(10,4)           NOT NULL,
        [threshold_pct]             NUMERIC(10,4)           NOT NULL,
        [below_threshold]           BIT                     NOT NULL DEFAULT 0,
        [consecutive_months_below]  INT                     NOT NULL DEFAULT 0,
        [alert_sent]                BIT                     NOT NULL DEFAULT 0,
        [import_id]                 BIGINT                  NOT NULL,
        [calculated_at]             DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_precision_kpi_history]           PRIMARY KEY ([id]),
        CONSTRAINT [FK_kpi_country]                     FOREIGN KEY ([country_id]) REFERENCES [dbo].[payroll_countries]([id]),
        CONSTRAINT [FK_kpi_import]                      FOREIGN KEY ([import_id])  REFERENCES [dbo].[payroll_calibration_imports]([id]),
        CONSTRAINT [UX_kpi_country_period]              UNIQUE ([country_id], [period])
    );
END
GO
