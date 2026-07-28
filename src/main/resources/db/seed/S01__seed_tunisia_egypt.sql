-- ═════════════════════════════════════════════════════════════════════════════
-- S01__seed_tunisia_egypt.sql
-- Full parameter seed for Tunisia (pays_id=179, TND) and Egypt (pays_id=53, EGP).
-- Clears and re-seeds BOTH:
--   • Old simulation model  : parameter_sets, social_charge_rates, payroll_rubriques_legacy
--   • New engine model      : payroll_countries, payroll_rubriques, payroll_parameter_sets,
--                             payroll_calculation_sequences
-- Run manually against DAF360_PAYROLL (not managed by Flyway).
-- ═════════════════════════════════════════════════════════════════════════════

-- ─── 0. CLEAN SLATE ──────────────────────────────────────────────────────────

-- Disable INSTEAD OF triggers so empty-table DELETEs don't raise errors
DISABLE TRIGGER [dbo].[trg_payroll_results_immutable]  ON [dbo].[payroll_results];
DISABLE TRIGGER [dbo].[trg_forex_snapshots_immutable]  ON [dbo].[payroll_forex_snapshots];

-- New engine model — delete deepest FK children first
DELETE FROM [dbo].[payroll_forex_snapshots];
DELETE FROM [dbo].[payroll_results];
DELETE FROM [dbo].[payroll_calibration_lines];
DELETE FROM [dbo].[payroll_precision_kpi_history];
DELETE FROM [dbo].[payroll_calibration_imports];
DELETE FROM [dbo].[payroll_calculation_sequences];
DELETE FROM [dbo].[payroll_parameter_sets];
DELETE FROM [dbo].[payroll_rubriques];
DELETE FROM [dbo].[payroll_countries];

-- Re-enable triggers
ENABLE TRIGGER [dbo].[trg_payroll_results_immutable]   ON [dbo].[payroll_results];
ENABLE TRIGGER [dbo].[trg_forex_snapshots_immutable]   ON [dbo].[payroll_forex_snapshots];

-- Old model — delete in FK order (children before parents)
DELETE FROM [dbo].[calibration_variances];        -- refs calibration_cycles
DELETE FROM [dbo].[simulation_results];           -- refs parameter_sets, cohort_definitions
DELETE FROM [dbo].[cohort_definitions];           -- refs parameter_sets
DELETE FROM [dbo].[calibration_cycles];           -- refs parameter_sets
DELETE FROM [dbo].[payroll_rubriques_legacy];     -- refs parameter_sets (CASCADE, but explicit)
DELETE FROM [dbo].[parameter_sets];               -- cascades: social_charge_rates, benefits_catalogue


-- ─── 1. OLD MODEL — TUNISIE (pays_id = 179) ──────────────────────────────────
DECLARE @tn_old_id BIGINT, @eg_old_id BIGINT;

INSERT INTO [dbo].[parameter_sets]
    (pays_id, version, fiscal_year, status, irpp_brackets,
     convergence_tolerance, max_convergence_iterations, calibration_threshold_pct,
     activated_at, change_rationale)
VALUES (
    179, 1, 2025, 'ACTIVE',
    N'[{"lower":0,"upper":5000,"rate":0.00},{"lower":5000,"upper":20000,"rate":0.26},{"lower":20000,"upper":30000,"rate":0.28},{"lower":30000,"upper":50000,"rate":0.32},{"lower":50000,"upper":null,"rate":0.35}]',
    0.01, 50, 1.00,
    SYSDATETIMEOFFSET(),
    N'Paramètres fiscaux et sociaux Tunisie 2025'
);
SET @tn_old_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[social_charge_rates]
    (parameter_set_id, contract_type, charge_code, charge_label,
     employee_rate, employer_rate, base_calculation, cap_amount)
VALUES
-- CDI
(@tn_old_id,'CDI','CNSS',    N'Cotisation CNSS',                                  0.0918,0.1657,'CAPPED_GROSS',6570.00),
(@tn_old_id,'CDI','TFP',     N'Taxe de Formation Professionnelle',                 0.0000,0.0200,'GROSS',       NULL),
(@tn_old_id,'CDI','FOPROLOS',N'Fonds Promotion Logement Salariés',                 0.0000,0.0100,'GROSS',       NULL),
(@tn_old_id,'CDI','AT_MP',   N'Accident Travail / Maladie Professionnelle',        0.0000,0.0100,'GROSS',       NULL),
-- CDD
(@tn_old_id,'CDD','CNSS',    N'Cotisation CNSS',                                  0.0918,0.1657,'CAPPED_GROSS',6570.00),
(@tn_old_id,'CDD','TFP',     N'Taxe de Formation Professionnelle',                 0.0000,0.0200,'GROSS',       NULL),
(@tn_old_id,'CDD','FOPROLOS',N'Fonds Promotion Logement Salariés',                 0.0000,0.0100,'GROSS',       NULL),
(@tn_old_id,'CDD','AT_MP',   N'Accident Travail / Maladie Professionnelle',        0.0000,0.0100,'GROSS',       NULL),
-- STAGE
(@tn_old_id,'STAGE','CNSS',  N'Cotisation CNSS stagiaire',                         0.0000,0.0446,'GROSS',       NULL),
(@tn_old_id,'STAGE','TFP',   N'Taxe de Formation Professionnelle',                 0.0000,0.0200,'GROSS',       NULL),
-- CIVP
(@tn_old_id,'CIVP','CNSS',   N'Cotisation CNSS CIVP',                              0.0000,0.0300,'GROSS',       NULL),
(@tn_old_id,'CIVP','TFP',    N'Taxe de Formation Professionnelle',                 0.0000,0.0050,'GROSS',       NULL),
(@tn_old_id,'CIVP','FOPROLOS',N'Fonds Promotion Logement Salariés CIVP',           0.0000,0.0050,'GROSS',       NULL);

INSERT INTO [dbo].[payroll_rubriques_legacy]
    (parameter_set_id, code, label_fr, label_en, nature, calc_mode,
     amount, rate, employer_share_pct, employee_share_pct,
     is_subject_to_social_charges, is_subject_to_irpp, direction, contract_types, is_active)
VALUES
(@tn_old_id,'INDEMNITE_TRANSPORT', N'Indemnité de transport',  N'Transport allowance', 'INDEMNITE','FIXE_MENSUEL',  150.0000,NULL,   0.000000,0.000000, 0,0,'CREDIT',NULL,       1),
(@tn_old_id,'TICKETS_RESTO',       N'Tickets restaurant',       N'Meal vouchers',       'AVANTAGE', 'FIXE_MENSUEL',  180.0000,NULL,   0.600000,0.400000, 0,0,'CREDIT',NULL,       1),
(@tn_old_id,'PRIME_RENDEMENT',     N'Prime de rendement',       N'Performance bonus',   'PRIME',    'POURCENTAGE_BRUT',NULL, 0.100000,0.000000,1.000000, 1,1,'CREDIT','CDI,CDD', 1),
(@tn_old_id,'PRIME_ANCIENNETE',    N'Prime d''ancienneté',      N'Seniority allowance', 'PRIME',    'FIXE_MENSUEL',  200.0000,NULL,   0.000000,1.000000, 1,1,'CREDIT','CDI',      1),
(@tn_old_id,'AVANCE_SALAIRE',      N'Avance sur salaire',       N'Salary advance',      'RETENUE',  'FIXE_MENSUEL',    0.0000,NULL,   0.000000,1.000000, 0,0,'DEBIT', NULL,       1);


-- ─── 2. OLD MODEL — ÉGYPTE (pays_id = 53) ────────────────────────────────────

INSERT INTO [dbo].[parameter_sets]
    (pays_id, version, fiscal_year, status, irpp_brackets,
     convergence_tolerance, max_convergence_iterations, calibration_threshold_pct,
     activated_at, change_rationale)
VALUES (
    53, 1, 2025, 'ACTIVE',
    N'[{"lower":0,"upper":40000,"rate":0.00},{"lower":40000,"upper":55000,"rate":0.10},{"lower":55000,"upper":70000,"rate":0.15},{"lower":70000,"upper":200000,"rate":0.20},{"lower":200000,"upper":400000,"rate":0.225},{"lower":400000,"upper":null,"rate":0.275}]',
    0.01, 50, 1.00,
    SYSDATETIMEOFFSET(),
    N'Egypt payroll parameters 2025'
);
SET @eg_old_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[social_charge_rates]
    (parameter_set_id, contract_type, charge_code, charge_label,
     employee_rate, employer_rate, base_calculation, cap_amount)
VALUES
-- CDI
(@eg_old_id,'CDI','NSSF', N'National Social Security Fund',  0.1100,0.1875,'CAPPED_GROSS',10900.00),
(@eg_old_id,'CDI','SHIF', N'Social Health Insurance Fund',   0.0000,0.0300,'GROSS',        NULL),
-- CDD
(@eg_old_id,'CDD','NSSF', N'National Social Security Fund',  0.1100,0.1875,'CAPPED_GROSS',10900.00),
(@eg_old_id,'CDD','SHIF', N'Social Health Insurance Fund',   0.0000,0.0300,'GROSS',        NULL),
-- STAGE
(@eg_old_id,'STAGE','SHIF',N'Social Health Insurance Fund',  0.0000,0.0150,'GROSS',        NULL),
-- CIVP
(@eg_old_id,'CIVP','NSSF', N'National Social Security Fund', 0.0000,0.0500,'CAPPED_GROSS',10900.00),
(@eg_old_id,'CIVP','SHIF', N'Social Health Insurance Fund',  0.0000,0.0150,'GROSS',        NULL);

INSERT INTO [dbo].[payroll_rubriques_legacy]
    (parameter_set_id, code, label_fr, label_en, nature, calc_mode,
     amount, rate, employer_share_pct, employee_share_pct,
     is_subject_to_social_charges, is_subject_to_irpp, direction, contract_types, is_active)
VALUES
(@eg_old_id,'MEAL_ALLOWANCE',      N'Indemnité repas',         N'Meal allowance',       'INDEMNITE','FIXE_MENSUEL',  800.0000,NULL,   0.000000,0.000000, 0,0,'CREDIT',NULL,       1),
(@eg_old_id,'TRANSPORT_ALLOWANCE', N'Indemnité transport',     N'Transport allowance',  'INDEMNITE','FIXE_MENSUEL',  500.0000,NULL,   0.000000,0.000000, 0,0,'CREDIT',NULL,       1),
(@eg_old_id,'PERFORMANCE_BONUS',   N'Prime de performance',    N'Performance bonus',    'PRIME',    'POURCENTAGE_BRUT',NULL, 0.100000,0.000000,1.000000, 1,1,'CREDIT','CDI,CDD', 1),
(@eg_old_id,'MEDICAL_DEDUCTION',   N'Déduction médicale',      N'Medical deduction',    'RETENUE',  'FIXE_MENSUEL',  200.0000,NULL,   0.000000,1.000000, 0,0,'DEBIT', NULL,       1);


-- ─── 3. NEW ENGINE MODEL — TUNISIE ───────────────────────────────────────────
DECLARE @tn_id BIGINT, @eg_id BIGINT, @tn_ps_id BIGINT, @eg_ps_id BIGINT;

INSERT INTO [dbo].[payroll_countries]
    (pays_id, currency_code, fiscal_year_start_month, active)
VALUES (179, 'TND', 1, 1);
SET @tn_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[payroll_rubriques]
    (country_id, code, label_fr, label_en,
     strate, nature, mode_calcul,
     assiette_code, param_key_taux, param_key_plafond, param_key_bareme,
     periodicite, prorata_applicable, display_order, active)
VALUES
-- Strate 1 — Brut
(@tn_id,'SALAIRE_BASE',  N'Salaire de base',               N'Base salary',              1,'GAIN',      'MONTANT_FIXE',     NULL,      'salaire_base_tn',     NULL,                   NULL,             'MENSUEL',1, 10,1),
-- Strate 3 — Cotisations
(@tn_id,'CNSS_SALARIE',  N'CNSS salarié (9,18 %)',         N'Employee CNSS',             3,'COTISATION','TAUX_PCT',         'STRATE_1','taux_cnss_sal_tn',    'plafond_cnss_mens_tn', NULL,             'MENSUEL',1, 20,1),
(@tn_id,'CNSS_PATRONAL', N'CNSS patronal (16,57 %)',       N'Employer CNSS',             3,'COTISATION','TAUX_PCT',         'STRATE_1','taux_cnss_empl_tn',   'plafond_cnss_mens_tn', NULL,             'MENSUEL',1, 25,1),
(@tn_id,'TFP',           N'TFP patronale (2 %)',           N'Vocational training tax',   3,'COTISATION','TAUX_PCT',         'STRATE_1','taux_tfp_tn',          NULL,                   NULL,             'MENSUEL',1, 30,1),
(@tn_id,'FOPROLOS',      N'FOPROLOS patronal (1 %)',       N'Housing fund (employer)',    3,'COTISATION','TAUX_PCT',         'STRATE_1','taux_foprolos_tn',     NULL,                   NULL,             'MENSUEL',1, 35,1),
(@tn_id,'AT_PATRONAL',   N'Accident du travail (empl)',    N'Work accident insurance',   3,'COTISATION','TAUX_PCT',         'STRATE_1','taux_at_tn',           NULL,                   NULL,             'MENSUEL',1, 40,1),
-- Strate 5 — Impôts et taxes
(@tn_id,'IRPP',          N'IRPP (barème progressif)',      N'Income tax (IRPP)',          5,'TAXE',      'ANNUALISE_BAREME', 'STRATE_4', NULL,                  NULL,                   'bareme_irpp_tn', 'MENSUEL',1, 50,1),
(@tn_id,'CSS',           N'Contribution Solidarité (1 %)', N'Solidarity contribution',   5,'TAXE',      'TAUX_PCT',         'STRATE_4','taux_css_tn',          NULL,                   NULL,             'MENSUEL',1, 60,1);

INSERT INTO [dbo].[payroll_parameter_sets]
    (country_id, version_number, status, effective_date, parameters, created_by, activated_at)
VALUES (
    @tn_id, 1, 'ACTIVE', '2025-01-01',
    N'{'
    + N'"salaire_base_tn":2500.0,'
    + N'"taux_cnss_sal_tn":9.18,'
    + N'"taux_cnss_empl_tn":16.57,'
    + N'"plafond_cnss_mens_tn":6570.0,'
    + N'"taux_tfp_tn":2.0,'
    + N'"taux_foprolos_tn":1.0,'
    + N'"taux_at_tn":0.4,'
    + N'"taux_css_tn":1.0,'
    + N'"seuil_css_annuel_tn":6000.0,'
    + N'"bareme_irpp_tn":['
    +   N'{"min":0,"max":5000,"rate":0},'
    +   N'{"min":5000,"max":20000,"rate":26},'
    +   N'{"min":20000,"max":30000,"rate":28},'
    +   N'{"min":30000,"max":50000,"rate":32},'
    +   N'{"min":50000,"rate":35}'
    + N']}',
    'seed', SYSDATETIMEOFFSET()
);
SET @tn_ps_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[payroll_calculation_sequences]
    (country_id, parameter_set_id, active, steps)
VALUES (
    @tn_id, @tn_ps_id, 1,
    N'['
    + N'{"stepOrder":1,"rubriqueCodes":["SALAIRE_BASE"],"outputVariable":"STRATE_1"},'
    + N'{"stepOrder":2,"rubriqueCodes":["CNSS_SALARIE","CNSS_PATRONAL","TFP","FOPROLOS","AT_PATRONAL"],"outputVariable":"STRATE_3"},'
    + N'{"stepOrder":3,"rubriqueCodes":["IRPP","CSS"],"outputVariable":"STRATE_5"}'
    + N']'
);


-- ─── 4. NEW ENGINE MODEL — ÉGYPTE ────────────────────────────────────────────

INSERT INTO [dbo].[payroll_countries]
    (pays_id, currency_code, fiscal_year_start_month, active)
VALUES (53, 'EGP', 7, 1);
SET @eg_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[payroll_rubriques]
    (country_id, code, label_fr, label_en,
     strate, nature, mode_calcul,
     assiette_code, param_key_taux, param_key_plafond, param_key_bareme,
     periodicite, prorata_applicable, display_order, active)
VALUES
-- Strate 1 — Brut
(@eg_id,'SALAIRE_BASE',  N'Salaire de base',              N'Base salary',               1,'GAIN',      'MONTANT_FIXE',     NULL,      'salaire_base_eg',    NULL,                 NULL,              'MENSUEL',1, 10,1),
-- Strate 3 — Cotisations
(@eg_id,'SI_SALARIE',   N'Assurance sociale salarié (11 %)', N'Employee social ins.',   3,'COTISATION','TAUX_PCT',         'STRATE_1','taux_si_sal_eg',     'plafond_si_mens_eg', NULL,              'MENSUEL',1, 20,1),
(@eg_id,'SI_PATRONAL',  N'Assurance sociale patronale (18,75 %)', N'Employer soc. ins.',3,'COTISATION','TAUX_PCT',         'STRATE_1','taux_si_empl_eg',    'plafond_si_mens_eg', NULL,              'MENSUEL',1, 25,1),
(@eg_id,'SHIF_PATRONAL',N'Assurance santé patronale (3 %)', N'Health ins. (employer)',  3,'COTISATION','TAUX_PCT',         'STRATE_1','taux_shif_eg',       NULL,                 NULL,              'MENSUEL',1, 30,1),
-- Strate 5 — Impôt
(@eg_id,'INCOME_TAX',   N'Impôt sur le revenu',           N'Income tax',                5,'TAXE',      'ANNUALISE_BAREME', 'STRATE_4', NULL,                NULL,                 'bareme_impot_eg', 'MENSUEL',1, 40,1);

INSERT INTO [dbo].[payroll_parameter_sets]
    (country_id, version_number, status, effective_date, parameters, created_by, activated_at)
VALUES (
    @eg_id, 1, 'ACTIVE', '2025-01-01',
    N'{'
    + N'"salaire_base_eg":15000.0,'
    + N'"taux_si_sal_eg":11.0,'
    + N'"taux_si_empl_eg":18.75,'
    + N'"plafond_si_mens_eg":10900.0,'
    + N'"taux_shif_eg":3.0,'
    + N'"bareme_impot_eg":['
    +   N'{"min":0,"max":40000,"rate":0},'
    +   N'{"min":40000,"max":55000,"rate":10},'
    +   N'{"min":55000,"max":70000,"rate":15},'
    +   N'{"min":70000,"max":200000,"rate":20},'
    +   N'{"min":200000,"max":400000,"rate":22.5},'
    +   N'{"min":400000,"rate":27.5}'
    + N']}',
    'seed', SYSDATETIMEOFFSET()
);
SET @eg_ps_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[payroll_calculation_sequences]
    (country_id, parameter_set_id, active, steps)
VALUES (
    @eg_id, @eg_ps_id, 1,
    N'['
    + N'{"stepOrder":1,"rubriqueCodes":["SALAIRE_BASE"],"outputVariable":"STRATE_1"},'
    + N'{"stepOrder":2,"rubriqueCodes":["SI_SALARIE","SI_PATRONAL","SHIF_PATRONAL"],"outputVariable":"STRATE_3"},'
    + N'{"stepOrder":3,"rubriqueCodes":["INCOME_TAX"],"outputVariable":"STRATE_5"}'
    + N']'
);


-- ─── 5. VERIFY ───────────────────────────────────────────────────────────────
SELECT 'OLD: parameter_sets'            AS tbl, COUNT(*) AS cnt FROM [dbo].[parameter_sets]
UNION ALL
SELECT 'OLD: social_charge_rates',               COUNT(*)       FROM [dbo].[social_charge_rates]
UNION ALL
SELECT 'OLD: payroll_rubriques_legacy',           COUNT(*)       FROM [dbo].[payroll_rubriques_legacy]
UNION ALL
SELECT 'NEW: payroll_countries',                  COUNT(*)       FROM [dbo].[payroll_countries]
UNION ALL
SELECT 'NEW: payroll_rubriques (engine)',          COUNT(*)       FROM [dbo].[payroll_rubriques]
UNION ALL
SELECT 'NEW: payroll_parameter_sets',             COUNT(*)       FROM [dbo].[payroll_parameter_sets]
UNION ALL
SELECT 'NEW: payroll_calculation_sequences',      COUNT(*)       FROM [dbo].[payroll_calculation_sequences];
