-- V5__calibration.sql
-- Calibration cycles and per-employee variance records.

CREATE TABLE [dbo].[calibration_cycles] (
    [id]                           BIGINT IDENTITY(1,1)  NOT NULL,
    [pays_id]                      BIGINT                NOT NULL,
    [period]                       NVARCHAR(7)           NOT NULL,  -- YYYY-MM
    [parameter_set_id]             BIGINT                NOT NULL,
    [status]                       NVARCHAR(255)         NOT NULL DEFAULT 'OPEN',  -- OPEN|CLOSED|REQUIRES_UPDATE
    [predicted_total_loaded_cost]  NUMERIC(18,4)         NULL,
    [actual_total_loaded_cost]     NUMERIC(18,4)         NULL,
    [variance_pct]                 NUMERIC(18,4)         NULL,
    [headcount]                    INT                   NULL,
    [closed_at]                    DATETIMEOFFSET(6)     NULL,
    [closed_by]                    BIGINT                NULL,
    [notes]                        NVARCHAR(255)         NULL,
    [created_by]                   BIGINT                NOT NULL,
    [created_at]                   DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT [PK_calibration_cycles]      PRIMARY KEY ([id]),
    CONSTRAINT [UX_calibration_pays_period] UNIQUE ([pays_id], [period]),
    CONSTRAINT [CK_calibration_status]      CHECK ([status] IN ('OPEN','CLOSED','REQUIRES_UPDATE')),
    CONSTRAINT [FK_cal_param_set]           FOREIGN KEY ([parameter_set_id]) REFERENCES [dbo].[parameter_sets]([id])
);
GO

CREATE TABLE [dbo].[calibration_variances] (
    [id]                    BIGINT IDENTITY(1,1)  NOT NULL,
    [cycle_id]              BIGINT                NOT NULL,
    [profile_user_id]       BIGINT                NOT NULL,
    [predicted_loaded_cost] NUMERIC(18,4)         NOT NULL,
    [actual_loaded_cost]    NUMERIC(18,4)         NOT NULL,
    [variance_amount]       NUMERIC(18,4)         NOT NULL,
    [variance_pct]          NUMERIC(18,4)         NOT NULL,
    [contract_type]         NVARCHAR(255)         NULL,
    [source_line]           INT                   NULL,
    [created_at]            DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT [PK_calibration_variances]  PRIMARY KEY ([id]),
    CONSTRAINT [FK_cv_cycle]               FOREIGN KEY ([cycle_id]) REFERENCES [dbo].[calibration_cycles]([id]) ON DELETE CASCADE
);
GO
