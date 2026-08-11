-- =============================================================================
-- S01__seed_tunisia_egypt.sql  (rev 2 -- V18/V19/V20 compatible)
-- Full parameter seed for Tunisia (pays_id=179, TND) and Egypt (pays_id=53, EGP).
-- Clears and re-seeds BOTH:
--   Old simulation model  : parameter_sets, social_charge_rates,
--                           benefits_catalogue, payroll_rubriques_legacy
--   New engine model      : payroll_countries, payroll_rubriques,
--                           payroll_parameter_sets, payroll_calculation_sequences
-- Also seeds mock simulation history:
--   cohort_definitions    (1 VALIDATED + 1 DRAFT per country)
--   simulation_results    (3 TN individual + 3 TN cohort,
--                          2 EG individual + 2 EG cohort)
-- Run manually against DAF360_PAYROLL (not managed by Flyway).
-- Safe to re-run (idempotent DELETE -> INSERT pattern).
-- =============================================================================

-- --- 0. CLEAN SLATE ----------------------------------------------------------

DISABLE TRIGGER [dbo].[trg_payroll_results_immutable]  ON [dbo].[payroll_results];
DISABLE TRIGGER [dbo].[trg_forex_snapshots_immutable]  ON [dbo].[payroll_forex_snapshots];

DELETE FROM [dbo].[payroll_forex_snapshots];
DELETE FROM [dbo].[payroll_results];
DELETE FROM [dbo].[payroll_calibration_lines];
DELETE FROM [dbo].[payroll_precision_kpi_history];
DELETE FROM [dbo].[payroll_calibration_imports];
DELETE FROM [dbo].[payroll_calculation_sequences];
DELETE FROM [dbo].[payroll_parameter_sets];
DELETE FROM [dbo].[payroll_rubriques];
DELETE FROM [dbo].[payroll_countries];

ENABLE TRIGGER [dbo].[trg_payroll_results_immutable]   ON [dbo].[payroll_results];
ENABLE TRIGGER [dbo].[trg_forex_snapshots_immutable]   ON [dbo].[payroll_forex_snapshots];

DELETE FROM [dbo].[calibration_variances];
DELETE FROM [dbo].[simulation_results];
DELETE FROM [dbo].[cohort_definitions];
DELETE FROM [dbo].[calibration_cycles];
DELETE FROM [dbo].[payroll_rubriques_legacy];
DELETE FROM [dbo].[parameter_sets];   -- cascades: social_charge_rates, benefits_catalogue


-- --- 1. OLD MODEL -- TUNISIE (pays_id = 179) ---------------------------------
DECLARE @tn_old_id BIGINT, @eg_old_id BIGINT;
DECLARE @tn_cohort_id BIGINT, @eg_cohort_id BIGINT;

INSERT INTO [dbo].[parameter_sets]
    (pays_id, version, fiscal_year, status, irpp_brackets,
     convergence_tolerance, max_convergence_iterations, calibration_threshold_pct,
     activated_at, change_rationale)
VALUES (
    179, 1, 2025, 'ACTIVE',
    N'[{"lower":0,"upper":5000,"rate":0.00},{"lower":5000,"upper":20000,"rate":0.26},{"lower":20000,"upper":30000,"rate":0.28},{"lower":30000,"upper":50000,"rate":0.32},{"lower":50000,"upper":null,"rate":0.35}]',
    0.01, 50, 1.00,
    SYSDATETIMEOFFSET(),
    N'Parametres fiscaux et sociaux Tunisie 2025'
);
SET @tn_old_id = SCOPE_IDENTITY();

-- social_charge_rates: V19 additions (formula_ee, formula_er, eval_order) included
INSERT INTO [dbo].[social_charge_rates]
    (parameter_set_id, contract_type, charge_code, charge_label,
     employee_rate, employer_rate, base_calculation, cap_amount,
     formula_ee, formula_er, eval_order)
VALUES
(@tn_old_id,'CDI','CNSS',    N'Cotisation CNSS',                   0.0918,0.1657,'CAPPED_GROSS',6570.00,NULL,NULL,10),
(@tn_old_id,'CDI','TFP',     N'Taxe de Formation Professionnelle', 0.0000,0.0200,'GROSS',NULL,NULL,NULL,20),
(@tn_old_id,'CDI','FOPROLOS',N'Fonds Promotion Logement Salaries', 0.0000,0.0100,'GROSS',NULL,NULL,NULL,30),
(@tn_old_id,'CDI','AT_MP',   N'Accident Travail / Maladie Prof.',  0.0000,0.0100,'GROSS',NULL,NULL,NULL,40),
(@tn_old_id,'CDD','CNSS',    N'Cotisation CNSS',                   0.0918,0.1657,'CAPPED_GROSS',6570.00,NULL,NULL,10),
(@tn_old_id,'CDD','TFP',     N'Taxe de Formation Professionnelle', 0.0000,0.0200,'GROSS',NULL,NULL,NULL,20),
(@tn_old_id,'CDD','FOPROLOS',N'Fonds Promotion Logement Salaries', 0.0000,0.0100,'GROSS',NULL,NULL,NULL,30),
(@tn_old_id,'CDD','AT_MP',   N'Accident Travail / Maladie Prof.',  0.0000,0.0100,'GROSS',NULL,NULL,NULL,40),
(@tn_old_id,'STAGE','CNSS',  N'Cotisation CNSS stagiaire',         0.0000,0.0446,'GROSS',NULL,NULL,NULL,10),
(@tn_old_id,'STAGE','TFP',   N'Taxe de Formation Professionnelle', 0.0000,0.0200,'GROSS',NULL,NULL,NULL,20),
(@tn_old_id,'CIVP','CNSS',   N'Cotisation CNSS CIVP',              0.0000,0.0300,'CAPPED_GROSS',6570.00,NULL,NULL,10),
(@tn_old_id,'CIVP','TFP',    N'Taxe de Formation Professionnelle', 0.0000,0.0050,'GROSS',NULL,NULL,NULL,20),
(@tn_old_id,'CIVP','FOPROLOS',N'Fonds Promotion Logement CIVP',   0.0000,0.0050,'GROSS',NULL,NULL,NULL,30);

-- benefits_catalogue: drives avantages checkboxes in the simulator UI
INSERT INTO [dbo].[benefits_catalogue]
    (parameter_set_id, benefit_code, benefit_label_fr, benefit_label_en,
     valuation_method, monthly_value, employee_share, employer_share, is_taxable)
VALUES
(@tn_old_id,'MEAL',     N'Tickets restaurant',       N'Meal vouchers',      'TAX_AUTHORITY',180.0000, 72.0000,108.0000,0),
(@tn_old_id,'TRANSPORT',N'Indemnite de transport',   N'Transport allowance','TAX_AUTHORITY',150.0000,  0.0000,150.0000,0),
(@tn_old_id,'HOUSING',  N'Aide au logement',         N'Housing allowance',  'ACTUAL_COST',  800.0000,  0.0000,800.0000,1),
(@tn_old_id,'SCHOOLING',N'Frais de scolarite',       N'School fees',        'ACTUAL_COST',  600.0000,  0.0000,600.0000,1);

-- payroll_rubriques_legacy (V8 schema FK; V18 adds formula_expression/display_order)
INSERT INTO [dbo].[payroll_rubriques_legacy]
    (parameter_set_id, code, label_fr, label_en, nature, calc_mode,
     amount, rate, employer_share_pct, employee_share_pct,
     is_subject_to_social_charges, is_subject_to_irpp, direction, contract_types, is_active,
     display_order, formula_expression)
VALUES
(@tn_old_id,'INDEMNITE_TRANSPORT',N'Indemnite de transport',N'Transport allowance','INDEMNITE','FIXE_MENSUEL',   150.0000,NULL,0.000000,0.000000,0,0,'CREDIT',NULL,     1,10,NULL),
(@tn_old_id,'TICKETS_RESTO',      N'Tickets restaurant',   N'Meal vouchers',      'AVANTAGE', 'FIXE_MENSUEL',   180.0000,NULL,0.600000,0.400000,0,0,'CREDIT',NULL,     1,20,NULL),
(@tn_old_id,'PRIME_RENDEMENT',    N'Prime de rendement',   N'Performance bonus',  'PRIME','POURCENTAGE_BRUT',   NULL,0.100000,0.000000,1.000000,1,1,'CREDIT','CDI,CDD',1,30,NULL),
(@tn_old_id,'PRIME_ANCIENNETE',   N'Prime d''anciennete',  N'Seniority allowance','PRIME','FIXE_MENSUEL',        200.0000,NULL,0.000000,1.000000,1,1,'CREDIT','CDI',    1,40,NULL),
(@tn_old_id,'PRIME_EXPERTISE',    N'Prime d''expertise',   N'Expertise premium',  'PRIME','FORMULE',             NULL,NULL,0.000000,1.000000,1,1,'CREDIT','CDI,CDD',1,45,N'BRUT * 0.030'),
(@tn_old_id,'AVANCE_SALAIRE',     N'Avance sur salaire',   N'Salary advance',     'RETENUE','FIXE_MENSUEL',       0.0000,NULL,0.000000,1.000000,0,0,'DEBIT', NULL,     1,50,NULL);


-- --- 2. OLD MODEL -- EGYPTE (pays_id = 53) -----------------------------------

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
     employee_rate, employer_rate, base_calculation, cap_amount,
     formula_ee, formula_er, eval_order)
VALUES
(@eg_old_id,'CDI',  'NSSF',N'National Social Security Fund',0.1100,0.1875,'CAPPED_GROSS',10900.00,NULL,NULL,10),
(@eg_old_id,'CDI',  'SHIF',N'Social Health Insurance Fund', 0.0000,0.0300,'GROSS',NULL,NULL,NULL,20),
(@eg_old_id,'CDD',  'NSSF',N'National Social Security Fund',0.1100,0.1875,'CAPPED_GROSS',10900.00,NULL,NULL,10),
(@eg_old_id,'CDD',  'SHIF',N'Social Health Insurance Fund', 0.0000,0.0300,'GROSS',NULL,NULL,NULL,20),
(@eg_old_id,'STAGE','SHIF',N'Social Health Insurance Fund', 0.0000,0.0150,'GROSS',NULL,NULL,NULL,10),
(@eg_old_id,'CIVP', 'NSSF',N'National Social Security Fund',0.0000,0.0500,'CAPPED_GROSS',10900.00,NULL,NULL,10),
(@eg_old_id,'CIVP', 'SHIF',N'Social Health Insurance Fund', 0.0000,0.0150,'GROSS',NULL,NULL,NULL,20);

INSERT INTO [dbo].[benefits_catalogue]
    (parameter_set_id, benefit_code, benefit_label_fr, benefit_label_en,
     valuation_method, monthly_value, employee_share, employer_share, is_taxable)
VALUES
(@eg_old_id,'MEAL',     N'Indemnite repas',    N'Meal allowance',     'TAX_AUTHORITY', 800.0000,0.0000, 800.0000,0),
(@eg_old_id,'TRANSPORT',N'Indemnite transport',N'Transport allowance','TAX_AUTHORITY', 500.0000,0.0000, 500.0000,0),
(@eg_old_id,'SCHOOLING',N'Frais de scolarite', N'School fees',        'ACTUAL_COST',  1500.0000,0.0000,1500.0000,1);

INSERT INTO [dbo].[payroll_rubriques_legacy]
    (parameter_set_id, code, label_fr, label_en, nature, calc_mode,
     amount, rate, employer_share_pct, employee_share_pct,
     is_subject_to_social_charges, is_subject_to_irpp, direction, contract_types, is_active,
     display_order, formula_expression)
VALUES
(@eg_old_id,'MEAL_ALLOWANCE',     N'Indemnite repas',        N'Meal allowance',     'INDEMNITE','FIXE_MENSUEL',   800.0000,NULL,0.000000,0.000000,0,0,'CREDIT',NULL,     1,10,NULL),
(@eg_old_id,'TRANSPORT_ALLOWANCE',N'Indemnite transport',    N'Transport allowance','INDEMNITE','FIXE_MENSUEL',   500.0000,NULL,0.000000,0.000000,0,0,'CREDIT',NULL,     1,20,NULL),
(@eg_old_id,'PERFORMANCE_BONUS',  N'Prime de performance',   N'Performance bonus',  'PRIME','POURCENTAGE_BRUT',  NULL,0.100000,0.000000,1.000000,1,1,'CREDIT','CDI,CDD',1,30,NULL),
(@eg_old_id,'RETENTION_ALLOWANCE',N'Allocation de retention',N'Retention allowance','PRIME','FORMULE',           NULL,NULL,0.000000,1.000000,1,1,'CREDIT','CDI',    1,35,N'BRUT * 0.020'),
(@eg_old_id,'MEDICAL_DEDUCTION',  N'Deduction medicale',     N'Medical deduction',  'RETENUE','FIXE_MENSUEL',    200.0000,NULL,0.000000,1.000000,0,0,'DEBIT', NULL,     1,40,NULL);


-- --- 3. NEW ENGINE MODEL -- TUNISIE ------------------------------------------
DECLARE @tn_id BIGINT, @eg_id BIGINT, @tn_ps_id BIGINT, @eg_ps_id BIGINT;

INSERT INTO [dbo].[payroll_countries] (pays_id, currency_code, fiscal_year_start_month, active)
VALUES (179, 'TND', 1, 1);
SET @tn_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[payroll_rubriques]
    (country_id, code, label_fr, label_en, strate, nature, mode_calcul,
     assiette_code, param_key_taux, param_key_plafond, param_key_bareme,
     periodicite, prorata_applicable, display_order, active)
VALUES
(@tn_id,'SALAIRE_BASE', N'Salaire de base',          N'Base salary',        1,'GAIN',      'MONTANT_FIXE',    NULL,      'salaire_base_tn',   NULL,                  NULL,            'MENSUEL',1,10,1),
(@tn_id,'CNSS_SALARIE', N'CNSS salarie (9,18 %)',    N'Employee CNSS',      3,'COTISATION','TAUX_PCT',        'STRATE_1','taux_cnss_sal_tn',  'plafond_cnss_mens_tn',NULL,            'MENSUEL',1,20,1),
(@tn_id,'CNSS_PATRONAL',N'CNSS patronal (16,57 %)',  N'Employer CNSS',      3,'COTISATION','TAUX_PCT',        'STRATE_1','taux_cnss_empl_tn', 'plafond_cnss_mens_tn',NULL,            'MENSUEL',1,25,1),
(@tn_id,'TFP',          N'TFP patronale (2 %)',      N'Vocational training',3,'COTISATION','TAUX_PCT',        'STRATE_1','taux_tfp_tn',        NULL,                  NULL,            'MENSUEL',1,30,1),
(@tn_id,'FOPROLOS',     N'FOPROLOS patronal (1 %)',  N'Housing fund',       3,'COTISATION','TAUX_PCT',        'STRATE_1','taux_foprolos_tn',   NULL,                  NULL,            'MENSUEL',1,35,1),
(@tn_id,'AT_PATRONAL',  N'Accident du travail',      N'Work accident ins.', 3,'COTISATION','TAUX_PCT',        'STRATE_1','taux_at_tn',         NULL,                  NULL,            'MENSUEL',1,40,1),
(@tn_id,'IRPP',         N'IRPP (bareme progressif)', N'Income tax (IRPP)',  5,'TAXE',      'ANNUALISE_BAREME','STRATE_4', NULL,               NULL,                  'bareme_irpp_tn','MENSUEL',1,50,1),
(@tn_id,'CSS',          N'Contribution Solidarite',  N'Solidarity contrib.',5,'TAXE',      'TAUX_PCT',        'STRATE_4','taux_css_tn',        NULL,                  NULL,            'MENSUEL',1,60,1);

INSERT INTO [dbo].[payroll_parameter_sets]
    (country_id, version_number, status, effective_date, parameters, created_by, activated_at)
VALUES (
    @tn_id, 1, 'ACTIVE', '2025-01-01',
    N'{"salaire_base_tn":2500.0,"taux_cnss_sal_tn":9.18,"taux_cnss_empl_tn":16.57,"plafond_cnss_mens_tn":6570.0,"taux_tfp_tn":2.0,"taux_foprolos_tn":1.0,"taux_at_tn":1.0,"taux_css_tn":1.0,"seuil_css_annuel_tn":6000.0,"bareme_irpp_tn":[{"min":0,"max":5000,"rate":0},{"min":5000,"max":20000,"rate":26},{"min":20000,"max":30000,"rate":28},{"min":30000,"max":50000,"rate":32},{"min":50000,"rate":35}]}',
    'seed', SYSDATETIMEOFFSET()
);
SET @tn_ps_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[payroll_calculation_sequences] (country_id, parameter_set_id, active, steps)
VALUES (
    @tn_id, @tn_ps_id, 1,
    N'[{"stepOrder":1,"rubriqueCodes":["SALAIRE_BASE"],"outputVariable":"STRATE_1"},{"stepOrder":2,"rubriqueCodes":["CNSS_SALARIE","CNSS_PATRONAL","TFP","FOPROLOS","AT_PATRONAL"],"outputVariable":"STRATE_3"},{"stepOrder":3,"rubriqueCodes":["IRPP","CSS"],"outputVariable":"STRATE_5"}]'
);


-- --- 4. NEW ENGINE MODEL -- EGYPTE -------------------------------------------

INSERT INTO [dbo].[payroll_countries] (pays_id, currency_code, fiscal_year_start_month, active)
VALUES (53, 'EGP', 7, 1);
SET @eg_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[payroll_rubriques]
    (country_id, code, label_fr, label_en, strate, nature, mode_calcul,
     assiette_code, param_key_taux, param_key_plafond, param_key_bareme,
     periodicite, prorata_applicable, display_order, active)
VALUES
(@eg_id,'SALAIRE_BASE', N'Salaire de base',              N'Base salary',         1,'GAIN',      'MONTANT_FIXE',    NULL,      'salaire_base_eg',  NULL,                NULL,             'MENSUEL',1,10,1),
(@eg_id,'SI_SALARIE',   N'Assurance sociale sal. 11 %',  N'Employee social ins.',3,'COTISATION','TAUX_PCT',        'STRATE_1','taux_si_sal_eg',   'plafond_si_mens_eg',NULL,             'MENSUEL',1,20,1),
(@eg_id,'SI_PATRONAL',  N'Assurance sociale empl. 18.75%',N'Employer social ins.',3,'COTISATION','TAUX_PCT',       'STRATE_1','taux_si_empl_eg',  'plafond_si_mens_eg',NULL,             'MENSUEL',1,25,1),
(@eg_id,'SHIF_PATRONAL',N'Assurance sante empl. 3 %',   N'Health ins. (empl.)', 3,'COTISATION','TAUX_PCT',        'STRATE_1','taux_shif_eg',     NULL,                NULL,             'MENSUEL',1,30,1),
(@eg_id,'INCOME_TAX',   N'Impot sur le revenu',          N'Income tax',          5,'TAXE',      'ANNUALISE_BAREME','STRATE_4', NULL,              NULL,                'bareme_impot_eg','MENSUEL',1,40,1);

INSERT INTO [dbo].[payroll_parameter_sets]
    (country_id, version_number, status, effective_date, parameters, created_by, activated_at)
VALUES (
    @eg_id, 1, 'ACTIVE', '2025-01-01',
    N'{"salaire_base_eg":15000.0,"taux_si_sal_eg":11.0,"taux_si_empl_eg":18.75,"plafond_si_mens_eg":10900.0,"taux_shif_eg":3.0,"bareme_impot_eg":[{"min":0,"max":40000,"rate":0},{"min":40000,"max":55000,"rate":10},{"min":55000,"max":70000,"rate":15},{"min":70000,"max":200000,"rate":20},{"min":200000,"max":400000,"rate":22.5},{"min":400000,"rate":27.5}]}',
    'seed', SYSDATETIMEOFFSET()
);
SET @eg_ps_id = SCOPE_IDENTITY();

INSERT INTO [dbo].[payroll_calculation_sequences] (country_id, parameter_set_id, active, steps)
VALUES (
    @eg_id, @eg_ps_id, 1,
    N'[{"stepOrder":1,"rubriqueCodes":["SALAIRE_BASE"],"outputVariable":"STRATE_1"},{"stepOrder":2,"rubriqueCodes":["SI_SALARIE","SI_PATRONAL","SHIF_PATRONAL"],"outputVariable":"STRATE_3"},{"stepOrder":3,"rubriqueCodes":["INCOME_TAX"],"outputVariable":"STRATE_5"}]'
);


-- --- 5. MOCK COHORT DEFINITIONS ----------------------------------------------

INSERT INTO [dbo].[cohort_definitions]
    (pays_id, name, fiscal_year, parameter_set_id, status, total_loaded_cost, total_headcount)
VALUES
(179, N'Equipe Developpement TN 2025',        2025, @tn_old_id, 'VALIDATED', 19381.7000, 3),
(179, N'Simulation masse salariale TN Q3-25', 2025, @tn_old_id, 'DRAFT',     NULL,       0);

SELECT @tn_cohort_id = id FROM [dbo].[cohort_definitions]
WHERE pays_id = 179 AND name = N'Equipe Developpement TN 2025';

INSERT INTO [dbo].[cohort_definitions]
    (pays_id, name, fiscal_year, parameter_set_id, status, total_loaded_cost, total_headcount)
VALUES
(53, N'Engineering Team EG 2025',  2025, @eg_old_id, 'VALIDATED', 68239.2500, 2),
(53, N'EG Headcount Plan H2-2025', 2025, @eg_old_id, 'DRAFT',     NULL,       0);

SELECT @eg_cohort_id = id FROM [dbo].[cohort_definitions]
WHERE pays_id = 53 AND name = N'Engineering Team EG 2025';


-- --- 6. MOCK SIMULATION RESULTS ----------------------------------------------
-- FX rates (2025): 1 EUR = 3.32 TND | 1 USD = 3.08 TND
--                  1 EUR = 53.5 EGP  | 1 USD = 50.2 EGP
-- input_net = gross - employee_charges - irpp_amount  (always actual net in hand)
-- loaded_cost = gross + employer_charges
-- cost_net_ratio = loaded_cost / input_net

-- === 6.1  TUNISIE -- 3 simulations individuelles ============================

-- TN-A: CDI Net->Brut net=2500  |  3650 - 335.07 - 815 = 2500
INSERT INTO [dbo].[simulation_results]
    (pays_id,profile_user_id,parameter_set_id,simulation_type,contract_type,
     input_net,net_taxable,taxable_base,gross,loaded_cost,
     loaded_cost_eur,loaded_cost_usd,fx_rate_eur,fx_rate_usd,local_currency,
     irpp_amount,employee_charges,employer_charges,
     benefits_applied,rubriques_applied,
     iterations_used,convergence_ok,cohort_id,
     gross_with_benefits,cost_net_ratio,
     candidate_label,poste,grade,discipline,
     mode,simulated_by,simulated_at)
VALUES(179,NULL,@tn_old_id,'INDIVIDUAL','CDI',
 2500.0000,3314.9300,3314.9300,3650.0000,4400.8100,
 1325.5400,1428.8300,3.3200,3.0800,'TND',
 815.0000,335.0700,750.8100,
 N'[{"code":"MEAL","value":180},{"code":"TRANSPORT","value":150}]',
 N'[{"code":"INDEMNITE_TRANSPORT","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":150.00},{"code":"TICKETS_RESTO","nature":"AVANTAGE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":180.00},{"code":"PRIME_RENDEMENT","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":365.00},{"code":"PRIME_ANCIENNETE","nature":"PRIME","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":200.00},{"code":"PRIME_EXPERTISE","nature":"PRIME","calcMode":"FORMULE","direction":"CREDIT","amount":109.50}]',
 28,1,NULL,3980.0000,1.7603,
 N'Mohamed Trabelsi',N'Ingenieur Developpement',N'ENGINEER',N'INFORMATIQUE',
 'NET_TO_BRUT',1,DATEADD(MONTH,-6,SYSDATETIMEOFFSET()));

-- TN-B: CDI Net->Brut net=4000  |  6000 - 550.80 - 1449.20 = 4000
INSERT INTO [dbo].[simulation_results]
    (pays_id,profile_user_id,parameter_set_id,simulation_type,contract_type,
     input_net,net_taxable,taxable_base,gross,loaded_cost,
     loaded_cost_eur,loaded_cost_usd,fx_rate_eur,fx_rate_usd,local_currency,
     irpp_amount,employee_charges,employer_charges,
     benefits_applied,rubriques_applied,
     iterations_used,convergence_ok,cohort_id,
     gross_with_benefits,cost_net_ratio,
     candidate_label,poste,grade,discipline,
     mode,simulated_by,simulated_at)
VALUES(179,NULL,@tn_old_id,'INDIVIDUAL','CDI',
 4000.0000,5449.2000,5449.2000,6000.0000,7234.2000,
 2178.4000,2348.1200,3.3200,3.0800,'TND',
 1449.2000,550.8000,1234.2000,
 N'[{"code":"MEAL","value":180},{"code":"TRANSPORT","value":150}]',
 N'[{"code":"INDEMNITE_TRANSPORT","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":150.00},{"code":"TICKETS_RESTO","nature":"AVANTAGE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":180.00},{"code":"PRIME_RENDEMENT","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":600.00},{"code":"PRIME_ANCIENNETE","nature":"PRIME","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":200.00},{"code":"PRIME_EXPERTISE","nature":"PRIME","calcMode":"FORMULE","direction":"CREDIT","amount":180.00}]',
 31,1,NULL,6330.0000,1.8086,
 N'Sonia Khediri',N'Chef de Projet',N'SENIOR',N'GESTION_PROJET',
 'NET_TO_BRUT',1,DATEADD(MONTH,-4,SYSDATETIMEOFFSET()));

-- TN-C: CDI Brut->Net brut=8000  |  input_net=5175 (net calcule)
-- CNSS_EE=min(6570,8000)*9.18%=603.08  IRPP~2222  net=8000-603-2222=5175
INSERT INTO [dbo].[simulation_results]
    (pays_id,profile_user_id,parameter_set_id,simulation_type,contract_type,
     input_net,net_taxable,taxable_base,gross,loaded_cost,
     loaded_cost_eur,loaded_cost_usd,fx_rate_eur,fx_rate_usd,local_currency,
     irpp_amount,employee_charges,employer_charges,
     benefits_applied,rubriques_applied,
     iterations_used,convergence_ok,cohort_id,
     gross_with_benefits,cost_net_ratio,
     candidate_label,poste,grade,discipline,
     mode,simulated_by,simulated_at)
VALUES(179,NULL,@tn_old_id,'INDIVIDUAL','CDI',
 5175.0000,7396.9200,7396.9200,8000.0000,9408.5700,
 2833.9100,3054.7300,3.3200,3.0800,'TND',
 2222.0000,603.0800,1408.5700,
 N'[{"code":"MEAL","value":180},{"code":"TRANSPORT","value":150}]',
 N'[{"code":"INDEMNITE_TRANSPORT","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":150.00},{"code":"TICKETS_RESTO","nature":"AVANTAGE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":180.00},{"code":"PRIME_RENDEMENT","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":800.00},{"code":"PRIME_ANCIENNETE","nature":"PRIME","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":200.00},{"code":"PRIME_EXPERTISE","nature":"PRIME","calcMode":"FORMULE","direction":"CREDIT","amount":240.00}]',
 0,1,NULL,8330.0000,1.8183,
 N'Ramzi Laabidi',N'Architecte SI',N'EXPERT',N'INFORMATIQUE',
 'BRUT_TO_NET',1,DATEADD(MONTH,-2,SYSDATETIMEOFFSET()));

-- === 6.2  TUNISIE -- 3 resultats cohorte =====================================

INSERT INTO [dbo].[simulation_results]
    (pays_id,profile_user_id,parameter_set_id,simulation_type,contract_type,
     input_net,net_taxable,taxable_base,gross,loaded_cost,
     loaded_cost_eur,loaded_cost_usd,fx_rate_eur,fx_rate_usd,local_currency,
     irpp_amount,employee_charges,employer_charges,
     benefits_applied,rubriques_applied,
     iterations_used,convergence_ok,cohort_id,
     gross_with_benefits,cost_net_ratio,
     candidate_label,poste,grade,discipline,
     mode,simulated_by,simulated_at)
VALUES
(179,NULL,@tn_old_id,'COHORT','CDI',
 2200.0000,2879.0000,2879.0000,3170.0000,3810.0000,1147.6000,1237.0000,3.3200,3.0800,'TND',
 679.0000,291.0000,640.0000,
 N'[{"code":"MEAL","value":180}]',
 N'[{"code":"INDEMNITE_TRANSPORT","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":150.00},{"code":"PRIME_RENDEMENT","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":317.00}]',
 24,1,@tn_cohort_id,3350.0000,1.7318,
 N'Amine Ben Salem',N'Developpeur Full Stack',N'ENGINEER',N'INFORMATIQUE',
 'NET_TO_BRUT',1,DATEADD(MONTH,-3,SYSDATETIMEOFFSET())),
(179,NULL,@tn_old_id,'COHORT','CDI',
 3500.0000,4905.0000,4905.0000,5400.0000,6505.2000,1959.4000,2111.4000,3.3200,3.0800,'TND',
 1394.0000,495.7000,1105.2000,
 N'[{"code":"MEAL","value":180},{"code":"TRANSPORT","value":150}]',
 N'[{"code":"INDEMNITE_TRANSPORT","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":150.00},{"code":"TICKETS_RESTO","nature":"AVANTAGE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":180.00},{"code":"PRIME_RENDEMENT","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":540.00},{"code":"PRIME_ANCIENNETE","nature":"PRIME","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":200.00}]',
 29,1,@tn_cohort_id,5730.0000,1.8586,
 N'Nadia Haddad',N'Lead Developpeuse',N'SENIOR',N'INFORMATIQUE',
 'NET_TO_BRUT',1,DATEADD(MONTH,-3,SYSDATETIMEOFFSET())),
(179,NULL,@tn_old_id,'COHORT','CDI',
 4800.0000,6945.0000,6945.0000,7550.0000,9066.5000,2730.3000,2943.7000,3.3200,3.0800,'TND',
 1944.0000,601.0000,1516.5000,
 N'[{"code":"MEAL","value":180},{"code":"TRANSPORT","value":150}]',
 N'[{"code":"INDEMNITE_TRANSPORT","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":150.00},{"code":"TICKETS_RESTO","nature":"AVANTAGE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":180.00},{"code":"PRIME_RENDEMENT","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":755.00},{"code":"PRIME_ANCIENNETE","nature":"PRIME","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":200.00},{"code":"PRIME_EXPERTISE","nature":"PRIME","calcMode":"FORMULE","direction":"CREDIT","amount":226.50}]',
 33,1,@tn_cohort_id,7880.0000,1.8889,
 N'Walid Ferchichi',N'Tech Lead',N'SENIOR',N'INFORMATIQUE',
 'NET_TO_BRUT',1,DATEADD(MONTH,-3,SYSDATETIMEOFFSET()));

UPDATE [dbo].[cohort_definitions]
   SET total_loaded_cost = 3810.0000 + 6505.2000 + 9066.5000, total_headcount = 3
 WHERE id = @tn_cohort_id;


-- === 6.3  EGYPTE -- 2 simulations individuelles ==============================

-- EG-D: CDI Net->Brut net=20000  |  25050 - 1199 - 3851 = 20000
INSERT INTO [dbo].[simulation_results]
    (pays_id,profile_user_id,parameter_set_id,simulation_type,contract_type,
     input_net,net_taxable,taxable_base,gross,loaded_cost,
     loaded_cost_eur,loaded_cost_usd,fx_rate_eur,fx_rate_usd,local_currency,
     irpp_amount,employee_charges,employer_charges,
     benefits_applied,rubriques_applied,
     iterations_used,convergence_ok,cohort_id,
     gross_with_benefits,cost_net_ratio,
     candidate_label,poste,grade,discipline,
     mode,simulated_by,simulated_at)
VALUES(53,NULL,@eg_old_id,'INDIVIDUAL','CDI',
 20000.0000,23851.0000,23851.0000,25050.0000,27845.2500,
 520.4700,554.6900,53.5000,50.2000,'EGP',
 3851.0000,1199.0000,2795.2500,
 N'[{"code":"MEAL","value":800},{"code":"TRANSPORT","value":500}]',
 N'[{"code":"MEAL_ALLOWANCE","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":800.00},{"code":"TRANSPORT_ALLOWANCE","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":500.00},{"code":"PERFORMANCE_BONUS","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":2505.00},{"code":"RETENTION_ALLOWANCE","nature":"PRIME","calcMode":"FORMULE","direction":"CREDIT","amount":501.00}]',
 27,1,NULL,26350.0000,1.3923,
 N'Ahmed El-Sayed',N'Software Engineer',N'ENGINEER',N'INFORMATIQUE',
 'NET_TO_BRUT',1,DATEADD(MONTH,-5,SYSDATETIMEOFFSET()));

-- EG-E: CDI Brut->Net brut=45000  |  input_net=34693 (net calcule)
-- NSSF_EE=1199  IRPP~9108  net=45000-1199-9108=34693
INSERT INTO [dbo].[simulation_results]
    (pays_id,profile_user_id,parameter_set_id,simulation_type,contract_type,
     input_net,net_taxable,taxable_base,gross,loaded_cost,
     loaded_cost_eur,loaded_cost_usd,fx_rate_eur,fx_rate_usd,local_currency,
     irpp_amount,employee_charges,employer_charges,
     benefits_applied,rubriques_applied,
     iterations_used,convergence_ok,cohort_id,
     gross_with_benefits,cost_net_ratio,
     candidate_label,poste,grade,discipline,
     mode,simulated_by,simulated_at)
VALUES(53,NULL,@eg_old_id,'INDIVIDUAL','CDI',
 34693.0000,43801.0000,43801.0000,45000.0000,48393.7500,
 904.5600,963.8200,53.5000,50.2000,'EGP',
 9108.0000,1199.0000,3393.7500,
 N'[{"code":"MEAL","value":800},{"code":"TRANSPORT","value":500}]',
 N'[{"code":"MEAL_ALLOWANCE","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":800.00},{"code":"TRANSPORT_ALLOWANCE","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":500.00},{"code":"PERFORMANCE_BONUS","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":4500.00},{"code":"RETENTION_ALLOWANCE","nature":"PRIME","calcMode":"FORMULE","direction":"CREDIT","amount":900.00}]',
 0,1,NULL,46300.0000,1.3952,
 N'Sara Mostafa',N'Senior Software Engineer',N'SENIOR',N'INFORMATIQUE',
 'BRUT_TO_NET',1,DATEADD(MONTH,-1,SYSDATETIMEOFFSET()));

-- === 6.4  EGYPTE -- 2 resultats cohorte ======================================

INSERT INTO [dbo].[simulation_results]
    (pays_id,profile_user_id,parameter_set_id,simulation_type,contract_type,
     input_net,net_taxable,taxable_base,gross,loaded_cost,
     loaded_cost_eur,loaded_cost_usd,fx_rate_eur,fx_rate_usd,local_currency,
     irpp_amount,employee_charges,employer_charges,
     benefits_applied,rubriques_applied,
     iterations_used,convergence_ok,cohort_id,
     gross_with_benefits,cost_net_ratio,
     candidate_label,poste,grade,discipline,
     mode,simulated_by,simulated_at)
VALUES
(53,NULL,@eg_old_id,'COHORT','CDI',
 18000.0000,21501.0000,21501.0000,22700.0000,25144.0000,469.9800,500.8800,53.5000,50.2000,'EGP',
 3501.0000,1199.0000,2444.0000,
 N'[{"code":"MEAL","value":800},{"code":"TRANSPORT","value":500}]',
 N'[{"code":"MEAL_ALLOWANCE","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":800.00},{"code":"TRANSPORT_ALLOWANCE","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":500.00},{"code":"PERFORMANCE_BONUS","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":2270.00}]',
 25,1,@eg_cohort_id,24000.0000,1.3969,
 N'Karim Hamdy',N'Backend Developer',N'ENGINEER',N'INFORMATIQUE',
 'NET_TO_BRUT',1,DATEADD(MONTH,-2,SYSDATETIMEOFFSET())),
(53,NULL,@eg_old_id,'COHORT','CDI',
 30000.0000,37301.0000,37301.0000,38500.0000,43095.2500,805.5100,858.4700,53.5000,50.2000,'EGP',
 7301.0000,1199.0000,4595.2500,
 N'[{"code":"MEAL","value":800},{"code":"TRANSPORT","value":500}]',
 N'[{"code":"MEAL_ALLOWANCE","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":800.00},{"code":"TRANSPORT_ALLOWANCE","nature":"INDEMNITE","calcMode":"FIXE_MENSUEL","direction":"CREDIT","amount":500.00},{"code":"PERFORMANCE_BONUS","nature":"PRIME","calcMode":"POURCENTAGE_BRUT","direction":"CREDIT","amount":3850.00},{"code":"RETENTION_ALLOWANCE","nature":"PRIME","calcMode":"FORMULE","direction":"CREDIT","amount":770.00}]',
 30,1,@eg_cohort_id,39800.0000,1.4365,
 N'Mona El-Rashidy',N'DevOps Lead',N'SENIOR',N'INFORMATIQUE',
 'NET_TO_BRUT',1,DATEADD(MONTH,-2,SYSDATETIMEOFFSET()));

UPDATE [dbo].[cohort_definitions]
   SET total_loaded_cost = 25144.0000 + 43095.2500, total_headcount = 2
 WHERE id = @eg_cohort_id;


-- --- 7. VERIFY ---------------------------------------------------------------
SELECT tbl, cnt FROM (
    SELECT 'OLD: parameter_sets'        AS tbl, COUNT(*) AS cnt FROM [dbo].[parameter_sets]
    UNION ALL SELECT 'OLD: social_charge_rates',COUNT(*) FROM [dbo].[social_charge_rates]
    UNION ALL SELECT 'OLD: benefits_catalogue', COUNT(*) FROM [dbo].[benefits_catalogue]
    UNION ALL SELECT 'OLD: rubriques_legacy',   COUNT(*) FROM [dbo].[payroll_rubriques_legacy]
    UNION ALL SELECT 'OLD: cohort_definitions', COUNT(*) FROM [dbo].[cohort_definitions]
    UNION ALL SELECT 'OLD: simulation_results', COUNT(*) FROM [dbo].[simulation_results]
    UNION ALL SELECT 'NEW: payroll_countries',  COUNT(*) FROM [dbo].[payroll_countries]
    UNION ALL SELECT 'NEW: payroll_rubriques',  COUNT(*) FROM [dbo].[payroll_rubriques]
    UNION ALL SELECT 'NEW: payroll_param_sets', COUNT(*) FROM [dbo].[payroll_parameter_sets]
    UNION ALL SELECT 'NEW: calc_sequences',     COUNT(*) FROM [dbo].[payroll_calculation_sequences]
) t ORDER BY tbl;
-- Expected:
--   OLD: benefits_catalogue     7  (TN:4 + EG:3)
--   OLD: cohort_definitions     4  (TN: 1 VALIDATED + 1 DRAFT  |  EG: same)
--   OLD: parameter_sets         2  (TN + EG)
--   OLD: rubriques_legacy      11  (TN:6 + EG:5)
--   OLD: simulation_results    10  (TN:3 indiv + 3 cohort  |  EG:2 indiv + 2 cohort)
--   OLD: social_charge_rates   20  (TN:13 + EG:7)
--   NEW: calc_sequences         2
--   NEW: payroll_countries      2
--   NEW: payroll_param_sets     2
--   NEW: payroll_rubriques     13  (TN:8 + EG:5)
