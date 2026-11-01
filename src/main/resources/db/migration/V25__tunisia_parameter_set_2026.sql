-- =============================================================================
-- V25__tunisia_parameter_set_2026.sql
-- DAF360 Payroll -- Jeu de paramètres Tunisie 2026 (ancien moteur -- parameter_sets)
-- Source : DAF360_Questions_Paie_Tunisie_Fa-03-08-26.xlsx (cabinet de paie + équipe
-- RH ARX Tunisie), vérifié contre le schéma réel et le jeu de paramètres 2025 déjà
-- ACTIVE en base (id=9) le 2026-10-22.
--
-- STRATÉGIE : ce script crée une NOUVELLE ligne parameter_sets (2025 n'est jamais
-- modifiée) et reporte TOUTES les charges/avantages/rubriques 2025 existants dans le
-- nouveau jeu 2026, puis applique UNIQUEMENT les changements confirmés par le
-- questionnaire. Rien de légalement obligatoire n'est perdu silencieusement (TFP,
-- FOPROLOS, AT_MP, HOUSING, SCHOOLING, et les 4 primes/retenue existantes restent).
--
-- NE PAS EXÉCUTER sans relecture -- script généré pour revue, pas pour exécution
-- automatique. Conserver le statut DRAFT : l'activation se fait via le workflow
-- applicatif (HR Manager -> Finance Manager), jamais par SQL direct.
-- =============================================================================

USE [DAF360_PAYROLL];
GO

DECLARE @pays_id BIGINT;
DECLARE @param_set_id BIGINT;
DECLARE @old_param_set_id BIGINT = 9; -- Tunisie 2025, ACTIVE, vérifié le 2026-10-22

SELECT @pays_id = id FROM pays_ref WHERE iso_code = 'TN';
IF @pays_id IS NULL
BEGIN
    RAISERROR('pays_ref: Tunisie introuvable (iso_code=TN). Vérifier la sync.', 16, 1);
    RETURN;
END
-- @pays_id attendu = 179 (vérifié le 2026-10-22)

-- ── BLOC 1 : Parameter Set ───────────────────────────────────────────────────
-- CORRECTION 2026-10-22 (suite à un échec réel en SSMS) : UX_parameter_sets_pays_ver
-- est un index unique sur (pays_id, version), et "version" est une séquence qui
-- s'incrémente sur TOUT l'historique d'un pays, pas une valeur qui repart à 1 par
-- année fiscale. La Tunisie a déjà version=1 (le jeu 2025, id=9) -- insérer un
-- second version=1 viole la contrainte. @next_version calcule donc dynamiquement
-- la prochaine valeur plutôt que de la coder en dur.
DECLARE @next_version INT = (SELECT ISNULL(MAX(version), 0) + 1 FROM parameter_sets WHERE pays_id = @pays_id);

IF NOT EXISTS (SELECT 1 FROM parameter_sets WHERE pays_id = @pays_id AND fiscal_year = 2026)
BEGIN
    INSERT INTO parameter_sets (
        pays_id, version, fiscal_year, status, irpp_brackets,
        convergence_tolerance, calibration_threshold_pct, created_at
    ) VALUES (
        @pays_id, @next_version, 2026, 'DRAFT',
        -- Nouveau barème IRPP entré en vigueur le 01/01/2025 (confirmé cabinet).
        -- Même format JSON que le jeu 2025 existant (bornes continues, upper=lower
        -- du palier suivant, dernier palier upper=null). Les tranches "5 001 à
        -- 10 000" etc. du questionnaire sont traduites en bornes continues pour
        -- rester cohérentes avec ce que IrppCalculatorService attend déjà.
        N'[{"lower":0,"upper":5000,"rate":0.00},{"lower":5000,"upper":10000,"rate":0.15},{"lower":10000,"upper":20000,"rate":0.25},{"lower":20000,"upper":30000,"rate":0.30},{"lower":30000,"upper":40000,"rate":0.33},{"lower":40000,"upper":50000,"rate":0.36},{"lower":50000,"upper":70000,"rate":0.38},{"lower":70000,"upper":null,"rate":0.40}]',
        0.01,  -- tolérance convergence 0.01 TND (identique à 2025, redemandé par RH ARX)
        0.01,  -- seuil calibration 0.01 % -- ATTENTION : stocké comme NOMBRE DE
               -- POURCENTAGE, pas une fraction (vérifié : CalibrationImportService
               -- compare variancePct, lui-même en points de pourcentage, contre
               -- cette valeur telle quelle -- "1.00" = 1 %, donc "0.01 %" = 0.01,
               -- PAS 0.0001 comme le script d'origine du questionnaire le supposait)
        SYSDATETIMEOFFSET()
    );
    SET @param_set_id = SCOPE_IDENTITY();
    PRINT 'Parameter Set Tunisie 2026 créé (id=' + CAST(@param_set_id AS VARCHAR) + ')';
END
ELSE
BEGIN
    SELECT @param_set_id = id FROM parameter_sets WHERE pays_id = @pays_id AND fiscal_year = 2026;
    PRINT 'Parameter Set Tunisie 2026 déjà existant, id=' + CAST(@param_set_id AS VARCHAR) + ' -- script idempotent, sections suivantes sautées si déjà peuplées.';
END
GO

-- ── BLOC 2 : Social Charge Rates ─────────────────────────────────────────────
-- IMPORTANT (vérifié dans TopologicalEvaluator.java) : contract_type est comparé
-- par égalité STRICTE (pas de liste séparée par virgules comme pour les
-- rubriques) -- une charge qui s'applique à CDI+CDD+STAGE a besoin d'UNE LIGNE
-- PAR TYPE DE CONTRAT, pas d'une seule ligne avec "CDI,CDD,STAGE".

DECLARE @pays_id BIGINT = (SELECT id FROM pays_ref WHERE iso_code = 'TN');
DECLARE @param_set_id BIGINT = (SELECT id FROM parameter_sets WHERE pays_id = @pays_id AND fiscal_year = 2026);
DECLARE @old_param_set_id BIGINT = 9;

IF NOT EXISTS (SELECT 1 FROM social_charge_rates WHERE parameter_set_id = @param_set_id)
BEGIN
    -- CORRECTION 2026-10-22 : "SIVP" dans le questionnaire cabinet n'est PAS un
    -- type de contrat distinct de CIVP -- c'est l'ANCIEN nom du MÊME dispositif,
    -- confirmé explicitement par l'utilisateur ("SIVP has a new name which is
    -- CIVP"). Une première version de ce script avait à tort créé un contract_type
    -- 'SIVP' séparé (et une migration applicative V26/V103 pour le supporter) --
    -- entièrement annulé en base le même jour. L'exonération totale CNSS décrite
    -- pour "SIVP" (Q13) s'applique donc au CIVP déjà existant, pas à un nouveau
    -- type. Pas de ligne CSS/CNSS "SIVP" séparée nulle part ci-dessous.

    -- 2a) Report intégral des charges 2025 qui NE CHANGENT PAS pour 2026 :
    --     TFP, FOPROLOS, AT_MP (toutes lignes), et STAGE|CNSS (le questionnaire ne
    --     le mentionne pas). CIVP|CNSS est EXCLU ici : réinséré en 2b avec
    --     l'exonération totale confirmée pour 2026 (voir ci-dessus).
    INSERT INTO social_charge_rates (
        parameter_set_id, contract_type, charge_code, charge_label,
        employee_rate, employer_rate, base_calculation, cap_amount,
        formula_ee, formula_er, eval_order
    )
    SELECT @param_set_id, contract_type, charge_code, charge_label,
           employee_rate, employer_rate, base_calculation, cap_amount,
           formula_ee, formula_er, eval_order
    FROM social_charge_rates
    WHERE parameter_set_id = @old_param_set_id
      AND NOT (charge_code = 'CNSS' AND contract_type IN ('CDI', 'CDD', 'CIVP'));

    -- 2b) CNSS -- CDI/CDD : taux salarié corrigé 9.18% -> 9.68% (loi de finances
    --     2025, confirmé cabinet), taux patronal 16.57% INCHANGÉ (déjà correct en
    --     base), SUPPRESSION DU PLAFOND (Q12 : "pas de plafond d'assiette" -- la
    --     base 2025 plafonnait à 6570 TND, ce qui contredit la réponse 2026).
    --     CIVP : exonération TOTALE salarié ET patronal (Q13, via le renommage
    --     SIVP->CIVP ci-dessus) -- la base 2025 avait un taux patronal réduit de
    --     3% (partiel, pas une exonération totale), remplacé ici par 0%/0%.
    INSERT INTO social_charge_rates (
        parameter_set_id, contract_type, charge_code, charge_label,
        employee_rate, employer_rate, base_calculation, cap_amount, eval_order
    ) VALUES
        (@param_set_id, 'CDI',  'CNSS', 'Cotisation CNSS', 0.0968, 0.1657, 'GROSS', NULL, 0),
        (@param_set_id, 'CDD',  'CNSS', 'Cotisation CNSS', 0.0968, 0.1657, 'GROSS', NULL, 0),
        (@param_set_id, 'CIVP', 'CNSS', 'Cotisation CNSS CIVP (exonérée)', 0, 0, 'GROSS', NULL, 0);

    -- 2c) CSS -- Contribution de Solidarité Sociale, NOUVELLE charge pour 2026
    --     (absente du jeu 2025, confirmée Q11 : "0,5% durant l'année 2026"), part
    --     salariale uniquement, sans plafond. Le questionnaire citait "CDI, CDD,
    --     STAGE, SIVP, CIVP" (5 entrées) pour cette charge -- devenu 4 types
    --     distincts une fois SIVP et CIVP reconnus comme le même type.
    INSERT INTO social_charge_rates (
        parameter_set_id, contract_type, charge_code, charge_label,
        employee_rate, employer_rate, base_calculation, cap_amount, eval_order
    ) VALUES
        (@param_set_id, 'CDI',  'CSS', 'Contribution de Solidarité Sociale', 0.0050, 0, 'GROSS', NULL, 5),
        (@param_set_id, 'CDD',  'CSS', 'Contribution de Solidarité Sociale', 0.0050, 0, 'GROSS', NULL, 5),
        (@param_set_id, 'STAGE','CSS', 'Contribution de Solidarité Sociale', 0.0050, 0, 'GROSS', NULL, 5),
        (@param_set_id, 'CIVP', 'CSS', 'Contribution de Solidarité Sociale', 0.0050, 0, 'GROSS', NULL, 5);

    -- 2d) NON TRAITÉ -- à décider avant activation, PAS deviné ici :
    --
    --   * CNRPS : apparaît dans le journal de paie réel (colonne R) mais le taux
    --     n'a pas été fourni par le cabinet (Q03 incomplète). Aucune ligne
    --     insérée -- confirmer le taux avant d'activer ce jeu de paramètres.
    --
    --   * Exonération CNSS patronale CDI post-CIVP (2 ans, base plafonnée à
    --     600 TND -- Q14) : IMPOSSIBLE à représenter avec ce schéma tel quel.
    --     contract_type est comparé par égalité stricte et les seules valeurs
    --     existantes pour la Tunisie sont CDI/CDD/CIVP/STAGE -- un CDI "ex-CIVP
    --     depuis moins de 2 ans" n'est pas distinguable d'un CDI ordinaire.
    --     employee_payroll_config n'a pas non plus de colonne de date de
    --     conversion CIVP->CDI. Nécessite soit une nouvelle valeur de
    --     contract_type que l'application reconnaît, soit une nouvelle colonne de
    --     suivi + logique applicative au moment du calcul --
    --     hors périmètre d'une migration de données pure.

    PRINT 'social_charge_rates Tunisie 2026 peuplé.';
END
ELSE
    PRINT 'social_charge_rates Tunisie 2026 déjà peuplé, section sautée.';
GO

-- ── BLOC 3 : Benefits Catalogue ──────────────────────────────────────────────

DECLARE @pays_id BIGINT = (SELECT id FROM pays_ref WHERE iso_code = 'TN');
DECLARE @param_set_id BIGINT = (SELECT id FROM parameter_sets WHERE pays_id = @pays_id AND fiscal_year = 2026);
DECLARE @old_param_set_id BIGINT = 9;

IF NOT EXISTS (SELECT 1 FROM benefits_catalogue WHERE parameter_set_id = @param_set_id)
BEGIN
    -- 3a) Report inchangé de TRANSPORT / HOUSING / SCHOOLING -- non mentionnés
    --     par le questionnaire, aucun changement demandé.
    INSERT INTO benefits_catalogue (
        parameter_set_id, benefit_code, benefit_label_fr, benefit_label_en,
        valuation_method, monthly_value, employee_share, employer_share, is_taxable
    )
    SELECT @param_set_id, benefit_code, benefit_label_fr, benefit_label_en,
           valuation_method, monthly_value, employee_share, employer_share, is_taxable
    FROM benefits_catalogue
    WHERE parameter_set_id = @old_param_set_id
      AND benefit_code <> 'MEAL';
      -- MEAL exclu : réinséré ci-dessous avec les valeurs 2026 corrigées.

    -- 3b) MEAL (tickets restaurant) -- CORRECTION CONFIRMÉE, la ligne 2025
    --     contredit la réponse cabinet 2026 sur 3 points :
    --       - valeur : 180 TND -> 198 TND (9 TND x 22 tickets, Q29)
    --       - répartition : 72/108 (salarié/employeur) -> 0/198 (100% employeur,
    --         Q29 : "pris en charge à 100% par l'employeur")
    --       - imposabilité : non imposable -> imposable en totalité (Q08 :
    --         "tickets restaurants ... imposable en totalité")
    --     Reste non cotisable CNSS (Q08 : "non cotisables dans la limite de
    --     3xSMIG horaire par repas" -- cette limite elle-même n'est pas
    --     modélisée ici, 198 TND/mois est sous ce plafond en pratique).
    INSERT INTO benefits_catalogue (
        parameter_set_id, benefit_code, benefit_label_fr, benefit_label_en,
        valuation_method, monthly_value, employee_share, employer_share, is_taxable
    ) VALUES (
        @param_set_id, 'MEAL', 'Tickets restaurant', 'Meal vouchers',
        'TAX_AUTHORITY', 198.00, 0.00, 198.00, 1
    );

    -- 3c) NON TRAITÉ -- à décider avant activation :
    --
    --   * Prime Fête Religieuse, Prime de Logement (Bloc 4 ②③ du questionnaire) :
    --     valeurs explicitement "variables"/"négociées individuellement", sans
    --     montant fixe -- benefits_catalogue exige un monthly_value. Aucune ligne
    --     insérée. La Prime de Logement semble par ailleurs recouper le benefit
    --     HOUSING déjà existant (800 TND, reporté ci-dessus) mais HOUSING ne
    --     porte aucun champ "cotisable" (voir aussi 3d) -- à clarifier avec le
    --     cabinet : s'agit-il du même avantage, et doit-il devenir cotisable ?
    --
    --   * Prime de Transport (Bloc 4 ④, "cotisable ET imposable") : benefits_
    --     catalogue n'a PAS de colonne cotisable/is_subject_to_social_charges --
    --     seules les rubriques (payroll_rubriques_legacy) ont ce concept. Un
    --     avantage cotisable doit être modélisé comme rubrique, pas comme
    --     benefit. Voir BLOC 5 pour la rubrique INDEMNITE_TRANSPORT existante,
    --     actuellement non cotisable/non imposable -- en contradiction avec
    --     cette réponse si les deux désignent la même chose. Non résolu ici.

    PRINT 'benefits_catalogue Tunisie 2026 peuplé.';
END
ELSE
    PRINT 'benefits_catalogue Tunisie 2026 déjà peuplé, section sautée.';
GO

-- ── BLOC 4 : Rubriques (payroll_rubriques_legacy) ────────────────────────────

DECLARE @pays_id BIGINT = (SELECT id FROM pays_ref WHERE iso_code = 'TN');
DECLARE @param_set_id BIGINT = (SELECT id FROM parameter_sets WHERE pays_id = @pays_id AND fiscal_year = 2026);
DECLARE @old_param_set_id BIGINT = 9;

IF NOT EXISTS (SELECT 1 FROM payroll_rubriques_legacy WHERE parameter_set_id = @param_set_id)
BEGIN
    -- 4a) Report inchangé des 5 rubriques 2025 non concernées par une
    --     correction : PRIME_RENDEMENT, PRIME_ANCIENNETE, PRIME_EXPERTISE,
    --     AVANCE_SALAIRE, INDEMNITE_TRANSPORT.
    INSERT INTO payroll_rubriques_legacy (
        parameter_set_id, code, label_fr, label_en, nature, calc_mode,
        amount, rate, employer_share_pct, employee_share_pct,
        is_subject_to_social_charges, is_subject_to_irpp, direction,
        contract_types, is_active, cap_amount, formula_expression, display_order
    )
    SELECT @param_set_id, code, label_fr, label_en, nature, calc_mode,
           amount, rate, employer_share_pct, employee_share_pct,
           is_subject_to_social_charges, is_subject_to_irpp, direction,
           contract_types, is_active, cap_amount, formula_expression, display_order
    FROM payroll_rubriques_legacy
    WHERE parameter_set_id = @old_param_set_id
      AND code <> 'TICKETS_RESTO';
      -- TICKETS_RESTO exclu : réinséré ci-dessous avec le montant 2026 corrigé.

    -- 4b) TICKETS_RESTO -- montant synchronisé avec la correction MEAL ci-dessus
    --     (180 -> 198 TND), pour que le bulletin et le benefit restent cohérents.
    INSERT INTO payroll_rubriques_legacy (
        parameter_set_id, code, label_fr, label_en, nature, calc_mode,
        amount, direction, is_subject_to_social_charges, is_subject_to_irpp,
        display_order
    ) VALUES (
        @param_set_id, 'TICKETS_RESTO', 'Tickets restaurant', 'Meal vouchers',
        'AVANTAGE', 'FIXE_MENSUEL', 198.00, 'CREDIT', 0, 0, 100
    );

    -- 4c) RAPPEL (régularisation) -- ligne modèle à montant 0, remplie au cas
    --     par cas au moment de la paie (même convention que AVANCE_SALAIRE déjà
    --     en base). Confirmé Q06 : "correction intégrée dans la ligne du mois
    --     qui suit".
    INSERT INTO payroll_rubriques_legacy (
        parameter_set_id, code, label_fr, label_en, nature, calc_mode,
        amount, direction, is_subject_to_social_charges, is_subject_to_irpp,
        display_order
    ) VALUES (
        @param_set_id, 'RAPPEL', 'Rappel / Régularisation', 'Adjustment', 'INDEMNITE',
        'FIXE_MENSUEL', 0.00, 'CREDIT', 0, 1, 110
    );

    -- 4d) NON TRAITÉ -- à décider avant activation, PAS deviné ici :
    --
    --   * BASE (salaire de base, Bloc 5 ①) : non applicable à ce moteur -- le
    --     brut est un paramètre D'ENTRÉE de PayrollSimulatorService.computeFromGross/
    --     computeFromNet, pas une rubrique calculée. Aucune ligne insérée.
    --
    --   * PR_PRES (Prime de Présence), AID_NNC, CONGES_PAYES (Bloc 5 ②⑦⑨) :
    --     listées sans montant ni taux dans le questionnaire -- impossible à
    --     chiffrer sans une valeur confirmée.
    --
    --   * PR_AID (Bloc 5 ⑤) : recouvre la même "Prime Fête Religieuse" déjà
    --     écartée en 3c (valeur variable).
    --
    --   * PR_EXC (Prime Exceptionnelle, Bloc 5 ⑥) : Q30 précise explicitement
    --     "pas de prime exceptionnelle habituelle, décidé uniquement par la
    --     direction si nécessaire" -- CECI N'EST PAS une rubrique récurrente de
    --     jeu de paramètres, c'est exactement le rôle de la table
    --     employee_payroll_bonus (bonus ponctuels, déjà en place, hors moteur de
    --     calcul). Intentionnellement NON ajoutée ici.
    --
    --   * PRIME_PERF (Bloc 5 ④, Q28 "minimum 0.3 x salaire, tous les 3 mois") :
    --     DIFFÉRENT de PRIME_RENDEMENT existant (10% du brut, mensuel). Une
    --     cadence trimestrielle n'a pas d'équivalent dans calc_mode
    --     (FIXE_MENSUEL/FIXE_JOURNALIER/POURCENTAGE_BRUT/POURCENTAGE_CHARGES/
    --     POURCENTAGE_PLAFONNE/FORMULE -- aucune notion de périodicité tous-les-
    --     N-mois). Nécessite une décision produit avant modélisation.
    --
    --   * HEURES_SUP (Bloc 5 ⑧, Q20 "fourni en net, intégré en brut à 75%,
    --     calcul à l'envers") : une inversion net->brut par rubrique individuelle
    --     n'existe dans aucun des 6 calc_mode de ce moteur (le mécanisme
    --     NEWTON_RAPHSON existe seulement dans le NOUVEAU moteur, sur
    --     payroll_rubriques, pas ici). Nécessite une décision d'ingénierie, pas
    --     une ligne de données.

    PRINT 'payroll_rubriques_legacy Tunisie 2026 peuplé.';
END
ELSE
    PRINT 'payroll_rubriques_legacy Tunisie 2026 déjà peuplé, section sautée.';
GO

-- ── VÉRIFICATION FINALE ──────────────────────────────────────────────────────
DECLARE @pays_id BIGINT = (SELECT id FROM pays_ref WHERE iso_code = 'TN');
DECLARE @param_set_id BIGINT = (SELECT id FROM parameter_sets WHERE pays_id = @pays_id AND fiscal_year = 2026);

SELECT 'parameter_sets (2026)'       AS tbl, @param_set_id AS id, status
FROM parameter_sets WHERE id = @param_set_id
UNION ALL
SELECT 'social_charge_rates',        COUNT(*), NULL FROM social_charge_rates        WHERE parameter_set_id = @param_set_id
UNION ALL
SELECT 'benefits_catalogue',         COUNT(*), NULL FROM benefits_catalogue         WHERE parameter_set_id = @param_set_id
UNION ALL
SELECT 'payroll_rubriques_legacy',   COUNT(*), NULL FROM payroll_rubriques_legacy   WHERE parameter_set_id = @param_set_id;

-- Comparatif ligne-à-ligne avec 2025, pour revue visuelle avant activation :
SELECT 'social_charge_rates' AS tbl, parameter_set_id, contract_type, charge_code, employee_rate, employer_rate, base_calculation, cap_amount
FROM social_charge_rates WHERE parameter_set_id IN (9, (SELECT id FROM parameter_sets WHERE pays_id = (SELECT id FROM pays_ref WHERE iso_code='TN') AND fiscal_year = 2026))
ORDER BY charge_code, contract_type, parameter_set_id;
GO
