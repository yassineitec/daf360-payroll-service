-- ─────────────────────────────────────────────────────────────────────────────
-- Assistant & FAQ (Administration › Assistant & FAQ) — même table que help_faq (RH) et
-- fact_help_faq (Finance). Questions / réponses saisies par les super-administrateurs paie,
-- en plus de la FAQ livrée avec l'application. L'assistant les affiche pour la page indiquée
-- (`page_key` = la clé de guide de la page, ex. 'home', 'simulator').
-- Français obligatoire ; anglais et arabe facultatifs (l'écran retombe sur le français).
-- Idempotent.
-- ─────────────────────────────────────────────────────────────────────────────
IF OBJECT_ID('dbo.payroll_help_faq', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.payroll_help_faq (
        id           BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_payroll_help_faq PRIMARY KEY,
        page_key     NVARCHAR(80)   NOT NULL,
        question_fr  NVARCHAR(300)  NOT NULL,
        answer_fr    NVARCHAR(2000) NOT NULL,
        question_en  NVARCHAR(300)  NULL,
        answer_en    NVARCHAR(2000) NULL,
        question_ar  NVARCHAR(300)  NULL,
        answer_ar    NVARCHAR(2000) NULL,
        order_index  INT            NOT NULL CONSTRAINT DF_payroll_help_faq_order   DEFAULT 0,
        is_active    BIT            NOT NULL CONSTRAINT DF_payroll_help_faq_active  DEFAULT 1,
        created_at   DATETIME2      NOT NULL CONSTRAINT DF_payroll_help_faq_created DEFAULT SYSDATETIME(),
        created_by   BIGINT         NULL,
        updated_at   DATETIME2      NULL,
        updated_by   BIGINT         NULL
    );
    CREATE INDEX IX_payroll_help_faq_page ON dbo.payroll_help_faq (page_key, is_active, order_index);
END
