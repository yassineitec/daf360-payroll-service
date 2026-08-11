-- F.04: two cost lines auto-created when a ParameterSet is fully activated
CREATE TABLE payroll_budget_lines (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    parameter_set_id BIGINT          NOT NULL,
    pays_id          BIGINT          NOT NULL,
    period           VARCHAR(7)      NOT NULL,   -- YYYY-MM from the triggering calibration cycle
    line_type        VARCHAR(30)     NOT NULL,   -- EMPLOYEE_NET | EMPLOYER_LOADED
    monthly_amount   DECIMAL(18,4)   NOT NULL,
    monthly_eur      DECIMAL(18,4)   NULL,
    monthly_chf      DECIMAL(18,4)   NULL,
    headcount        INT             NULL,
    local_currency   VARCHAR(10)     NULL,
    created_at       DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET()
);

CREATE INDEX ix_pbl_pays_period  ON payroll_budget_lines (pays_id, period);
CREATE INDEX ix_pbl_paramset     ON payroll_budget_lines (parameter_set_id);

-- F.07: three forecast outputs (monthly / quarterly / annual) auto-created on activation
CREATE TABLE payroll_forecast_outputs (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    parameter_set_id BIGINT          NOT NULL,
    pays_id          BIGINT          NOT NULL,
    period           VARCHAR(7)      NOT NULL,
    forecast_type    VARCHAR(20)     NOT NULL,   -- MONTHLY | QUARTERLY | ANNUAL
    forecast_amount  DECIMAL(18,4)   NOT NULL,
    forecast_eur     DECIMAL(18,4)   NULL,
    forecast_chf     DECIMAL(18,4)   NULL,
    local_currency   VARCHAR(10)     NULL,
    headcount        INT             NULL,
    created_at       DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET()
);

CREATE INDEX ix_pfo_pays_period  ON payroll_forecast_outputs (pays_id, period);
CREATE INDEX ix_pfo_paramset     ON payroll_forecast_outputs (parameter_set_id);
