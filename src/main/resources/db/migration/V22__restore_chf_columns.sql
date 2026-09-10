-- =============================================================================
-- V22__restore_chf_columns.sql
--
-- Restaure simulation_results.loaded_cost_chf / fx_rate_chf, supprimees par
-- erreur le 2026-09-09 en rejouant V7 hors ordre sur PROD.
--
-- CE QUI S'EST PASSE
-- -----------------------------------------------------------------------------
-- V7 fait deux choses en deux lots : elle AJOUTE local_currency / loaded_cost_usd
-- / fx_rate_usd, puis elle SUPPRIME loaded_cost_chf / fx_rate_chf (« no production
-- data existed » a l'epoque). V14, plus tard, REAJOUTE ces deux colonnes CHF pour
-- l'affichage en 5 strates — et `SimulationResult` les mappe toujours.
--
-- V7 avait donc deja tourne. La rejouer a fait echouer le premier lot en
-- Msg 2705 (colonnes deja presentes, erreur au niveau instruction, benigne) mais
-- le SECOND lot, lui, s'est execute normalement : le DROP a repris effet et a
-- annule ce que V14 avait retabli.
--
-- LA LECON, ET ELLE EST GENERALE
-- -----------------------------------------------------------------------------
-- Une migration n'est re-jouable que si TOUS ses lots le sont. V7 est gardee
-- nulle part : son ADD echoue proprement quand la colonne existe, ce qui donne
-- l'illusion d'idempotence, pendant que son DROP mord a chaque passage. Un script
-- qui SUPPRIME sans garde est une bombe a retardement des qu'un script ULTERIEUR
-- recree l'objet.
--
-- DONNEES
-- -----------------------------------------------------------------------------
-- Les colonnes reviennent NULL sur toutes les lignes. Toute valeur CHF ecrite
-- entre V14 et le 2026-09-09 est perdue et n'est recuperable que depuis une
-- sauvegarde. Le calcul les recalcule a la simulation suivante ; l'historique
-- affiche des cellules vides pour les simulations passees.
--
--   sqlcmd -S <server> -d DAF360_PAYROLL -C -I -i V22__restore_chf_columns.sql
-- Re-jouable : garde par COL_LENGTH.
-- Created: 2026-09-09
-- =============================================================================

USE [DAF360_PAYROLL];
GO

IF COL_LENGTH('dbo.simulation_results', 'loaded_cost_chf') IS NULL
BEGIN
    ALTER TABLE [dbo].[simulation_results] ADD [loaded_cost_chf] DECIMAL(18,4) NULL;
    PRINT 'V22 : simulation_results.loaded_cost_chf restauree.';
END
ELSE PRINT 'V22 : loaded_cost_chf deja presente.';
GO

IF COL_LENGTH('dbo.simulation_results', 'fx_rate_chf') IS NULL
BEGIN
    ALTER TABLE [dbo].[simulation_results] ADD [fx_rate_chf] DECIMAL(18,6) NULL;
    PRINT 'V22 : simulation_results.fx_rate_chf restauree.';
END
ELSE PRINT 'V22 : fx_rate_chf deja presente.';
GO

-- Les six autres colonnes de V14 : elles n'etaient pas visees par le DROP de V7,
-- mais on verifie plutot que de supposer — c'est exactement le genre de suppose
-- qui a produit l'incident.
SELECT c.name AS colonne,
       CASE WHEN c.name IS NULL THEN '>>> MANQUANTE' ELSE 'presente' END AS etat
FROM (VALUES ('loaded_cost_chf'), ('fx_rate_chf'), ('gross_with_benefits'),
             ('cost_net_ratio'), ('candidate_label'), ('poste'),
             ('grade'), ('discipline'), ('local_currency'),
             ('loaded_cost_usd'), ('fx_rate_usd')) AS t(nom)
LEFT JOIN sys.columns c
       ON c.object_id = OBJECT_ID('dbo.simulation_results')
      AND c.name = t.nom;

PRINT 'V22 complete. Redemarrer payroll-backend si la simulation a ete appelee entre-temps.';
GO
