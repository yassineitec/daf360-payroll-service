-- =============================================================================
-- V26__payroll_countries_fx_rates.sql
-- DAF360 Payroll -- payroll_countries gagne eur_rate/usd_rate/chf_rate
--
-- Contexte : jusqu'ici, PayrollCountry.currencyCode est affiché dans l'admin
-- paie mais n'a AUCUN effet sur un calcul réel -- chaque simulation/cohorte/
-- calibration lit les taux depuis la config statique app.fx-rates (YAML).
-- Modifier la devise d'un pays dans l'admin ne fait donc que relabelliser les
-- futurs snapshots forex, sans jamais changer un montant calculé.
--
-- Cette migration fait de payroll_countries la source de vérité : elle ajoute
-- les 3 taux (EUR/USD/CHF) à côté de currency_code, puis seed les 2 lignes
-- existantes (Tunisie pays_id=179, Égypte pays_id=53) avec EXACTEMENT les
-- valeurs aujourd'hui actives dans application.yml -- app.fx-rates (aucun
-- override FX_* n'est défini dans docker-compose.dev.yml ni .env/.env.dev,
-- donc ce sont bien les valeurs par défaut du YAML qui sont en vigueur) :
--   "179" (TND) : eur=0.300, usd=0.325, chf=0.316
--   "53"  (EGP) : eur=0.019, usd=0.021, chf=0.020
-- Zéro changement de comportement au déploiement : FxSnapshotService lira
-- désormais ces colonnes en priorité, mais avec les mêmes valeurs qu'avant.
-- =============================================================================

USE [DAF360_PAYROLL];
GO

ALTER TABLE payroll_countries ADD eur_rate DECIMAL(18,6) NULL;
ALTER TABLE payroll_countries ADD usd_rate DECIMAL(18,6) NULL;
ALTER TABLE payroll_countries ADD chf_rate DECIMAL(18,6) NULL;
GO

UPDATE payroll_countries SET eur_rate = 0.300, usd_rate = 0.325, chf_rate = 0.316 WHERE pays_id = 179;
UPDATE payroll_countries SET eur_rate = 0.019, usd_rate = 0.021, chf_rate = 0.020 WHERE pays_id = 53;
GO
