-- V2__pays_users_ref.sql
-- Replicated read-only reference tables synced from DAF360_HR every 15 minutes.

CREATE TABLE [dbo].[pays_ref] (
    [id]            BIGINT          NOT NULL,
    [iso_code]      NVARCHAR(255)   NOT NULL,
    [french_label]  NVARCHAR(255)   NOT NULL,
    [devise]        NVARCHAR(255)   NOT NULL,
    CONSTRAINT [PK_pays_ref] PRIMARY KEY ([id])
);
GO

CREATE TABLE [dbo].[users_ref] (
    [id]            BIGINT          NOT NULL,
    [azure_oid]     NVARCHAR(255)   NULL,
    [full_name]     NVARCHAR(255)   NOT NULL,
    [email]         NVARCHAR(255)   NULL,
    [pays_id]       BIGINT          NULL,
    [role_name]     NVARCHAR(255)   NULL,
    CONSTRAINT [PK_users_ref] PRIMARY KEY ([id])
);
GO
