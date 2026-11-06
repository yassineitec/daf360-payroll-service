# New-Model Payroll Engine — Fix IRPP/CSS, Net, and Employer-Cost Calculation

## Problem

"Calcul de paie" (`POST /api/payroll/engine/run`) and "Résultats de paie" run on the
"new model" engine (`PayrollOrchestrator` + `modules.payroll`), a completely separate
calculation path from the Simulator's "old model" engine (`PayrollSimulatorService`,
already fixed and validated this session — see the design note at the bottom of this
file). A live investigation (2026-11-06) found the new engine is broken for every
currently-configured country, confirmed with live test data (Tunisia, gross=2500) and
confirmed via direct schema inspection to be systemic, not Tunisia-specific:

1. **IRPP and CSS (Egypt: INCOME_TAX) always compute to 0.00.** Every country's
   rubrique chain (`payroll_rubriques`) jumps straight from strate 3 (contributions) to
   strate 5 (taxes) — nothing produces a strate-4 amount, and the tax rubriques all read
   `assiette_code = 'STRATE_4'`, which `SystemVariableAggregator` initializes to zero
   when nothing populates it.
2. **"Net à payer" (`ResultPersister.java:79`, `aggregateNet`) is wired to `STRATE_5`**
   — the sum of the tax rubriques themselves — instead of `gross − employee charges −
   taxes`. Currently masked by bug 1 (shows 0); fixing bug 1 alone would make this
   display the *tax total*, which is worse. Must be fixed together with bug 1.
3. **"Coût employeur" (`aggregateEmployerCharges`/`loadedCost`) undercounts.** It is
   built from `CHARGES_ER`, injected by `PayrollOrchestrator.injectChargeVariables()`
   from the **old** model's `social_charge_rates` — completely bypassing the new
   model's own employer-side rubriques (Tunisia: `CNSS_PATRONAL`, `TFP`, `FOPROLOS`,
   `AT_PATRONAL`; Egypt: `SI_PATRONAL`, `SHIF_PATRONAL`) which get computed in the same
   run and appear in the response's `rubriqueDetails`, but are never read into the
   final total. Confirmed live (Tunisia, gross=2500): displayed `439.25`, true total
   from the rubrique breakdown `514.25` — undercounts by exactly TFP+FOPROLOS (`75`),
   every run.
4. **Employee- and employer-side rubriques are both tagged `strate = 3`**, with no
   other field distinguishing sides — confirmed generic across both countries (Tunisia:
   `CNSS_SALARIE` alongside 4 employer rubriques; Egypt: `SI_SALARIE` alongside
   `SI_PATRONAL`/`SHIF_PATRONAL`, all strate 3). A code comment in
   `SystemVariableAggregator` already admits this ("strate 3 used as an
   employer-charges proxy — a known simplification").
5. **Dead code**: `SystemVariableAggregator.populateResult()` computes these aggregates
   one way and has zero callers; `ResultPersister.persist()` (the one actually invoked)
   computes them a different way.
6. **Tunisia's new-model rates are independently stale**, unrelated to bugs 1–5: CNSS
   employee 9.18% (new) vs. 9.68% (old model, validated against the reference
   worksheet), CSS 1% flat (new) vs. 0.5% on a post-abattement base (old model), a
   5-tier IRPP bracket table (new) vs. the validated 8-tier table (old model). TFP (2%)
   and FOPROLOS (1%) exist only in the new model, with no old-model equivalent.

## Out of scope (explicitly, not forgotten)

- **Egypt's rate values.** There is no validated reference document for Egypt (unlike
  Tunisia's worksheet-audited rates). This fix corrects the *engine* for every country,
  but only *validates and corrects rates* for Tunisia. Egypt's new strate-4 rubrique
  (below) stops the hard-zero but is explicitly not claimed correct — a dedicated
  Egypt audit is a separate, future piece of work.
- **Retiring the old-model charge bridge.** `injectChargeVariables()` keeps running
  exactly as it does today. This fix makes its output unused for net/employer-cost
  (everything is derived from the new model's own rubriques instead), but does not
  remove the bridge call itself — a safe, separate future cleanup once no country
  depends on it.
- **The old-model Simulator engine.** Already fixed and validated this session, not
  touched here.
- Every other gap from the 2026-10-22 architecture audit (unplugged Calibration/
  Budget/Cohort routes, hidden admin cards, the new-model calibration-import dead end,
  etc.) — unrelated to this fix.

## Architecture

The engine-level fixes (strate-4 gap, net formula, employer-cost aggregation, dead
code) are country-agnostic code changes in `ResultPersister`/`SystemVariableAggregator`
— they apply to every row in `payroll_countries`, present or future, without
country-specific branching. The rate corrections are country-specific *data* — only
Tunisia's `payroll_rubriques` rows are changed.

```
PayrollOrchestrator.run(employeeId, paysId, period...)
  -> ContextLoader.load()          seeds JOURS_OUVRES/MOIS/ANNEE
                                    + (NEW) employee's isMarried/childrenCount
                                      from employee_payroll_config, for Tunisia's
                                      strate-4 formula to read
  -> SequenceValidator.validate()  unchanged
  -> per-rubrique CalculationDispatcher, strata 1 -> 3 -> (NEW) 4 -> 5, in
     displayOrder:
       strate 1  SALAIRE_BASE                           (unchanged)
       strate 3  CNSS_SALARIE / SI_SALARIE               payer=EMPLOYEE (NEW column)
                 CNSS_PATRONAL / TFP / FOPROLOS /
                 AT_PATRONAL / SI_PATRONAL / SHIF_PATRONAL  payer=EMPLOYER
       strate 4  (NEW) ASSIETTE_IRPP  FORMULE             Tunisia: real formula
                                                           Egypt: placeholder, flagged
       strate 5  IRPP / CSS / INCOME_TAX  reads STRATE_4  (now non-zero)
  -> charge bridge (injectChargeVariables())              UNCHANGED, still runs,
                                                           output no longer read
  -> ResultPersister.persist()     (REWRITTEN) derives aggregateNet/
                                    aggregateEmployerCharges from the rubriques
                                    actually computed this run, via `payer`
```

## Data model changes

**`daf360-sql` (`db-payroll`)**:
- New column `payroll_rubriques.payer NVARCHAR(10) NULL CHECK (payer IN ('EMPLOYEE','EMPLOYER') OR payer IS NULL)`.
  Backfilled in the same migration from existing naming: `CNSS_SALARIE`/`SI_SALARIE` →
  `EMPLOYEE`; `CNSS_PATRONAL`/`TFP`/`FOPROLOS`/`AT_PATRONAL`/`SI_PATRONAL`/
  `SHIF_PATRONAL` → `EMPLOYER`. Left `NULL` for `GAIN`/`TAXE`-nature rows (a tax is
  inherently withheld from the employee without needing this flag; an earning isn't a
  "charge" either side pays).
- New rows in `payroll_rubriques`: one strate-4 rubrique per country.
  - Tunisia (`country_id=7`): `ASSIETTE_IRPP`, `nature='TAXE'` or a new neutral nature
    if `TAXE` is semantically wrong for a pure intermediate base (to settle during
    implementation by checking how `nature` is actually consumed elsewhere),
    `mode_calcul='FORMULE'`, `assiette_code=NULL` (reads `STRATE_3`-level aggregates,
    not a prior strate-4), `strate=4`, `display_order` between the strate-3 and
    strate-5 rows. Formula replicates the old model's validated pipeline: annualize
    the monthly taxable base (`gross − employee-side STRATE_3 charges`, × 12), subtract
    abattement (`min(annual_base × abattement_rate, abattement_cap)`) and family
    deduction (`married ? family_married : 0) + min(children, family_max_children) ×
    family_per_child`), floor at zero, round up to the next whole unit, then divide by
    12 — producing a *monthly-equivalent* assiette so the existing `ANNUALISE_BAREME`
    calculator's own `× 12` step reconstructs the correct annual adjusted base before
    applying brackets. The exact SpEL/formula syntax is an implementation detail to
    verify against how `FORMULE` mode is actually evaluated (`CalculationDispatcher`/
    `modules.payroll.calculator.*`) — not pinned here.
  - Egypt (`country_id=8`): `ASSIETTE_INCOME_TAX`, same mechanics, but **no**
    abattement/family-deduction step — a plain monthly-equivalent annualization of the
    taxable base. Code comment must say explicitly this is unvalidated and exists only
    to stop the hard-zero, pending a dedicated Egypt audit.
  - `IRPP`/`CSS` (Tunisia) and `INCOME_TAX` (Egypt) rubriques: `assiette_code` changed
    from `'STRATE_4'` (dangling) to the new rubrique's own code, so the dependency is
    explicit and `SequenceValidator` can actually verify it resolves.
- New parameter keys on Tunisia's active `payroll_parameter_sets.parameters` JSON:
  abattement rate + annual cap, family deduction married/per-child/max-children
  amounts — same values as the old model's already-validated `ParameterSet` fields,
  referenced by the new `ASSIETTE_IRPP` rubrique's formula via the existing
  generic parameter-key-lookup mechanism (`param_key_*` fields, same pattern already
  used by `SALAIRE_BASE`'s `MONTANT_FIXE` mode).
- Rate corrections, Tunisia only, on existing `payroll_rubriques` rows: `CNSS_SALARIE`
  9.18% → 9.68%; `CSS` 1% flat on gross → 0.5% on the new strate-4 assiette (matching
  the old model's `TAXABLE_BASE2` behavior); `IRPP`'s bracket table → the validated
  8-tier table. Egypt's rates are untouched.

## Backend changes (`daf360-payroll-service`)

- **`ContextLoader.load()`**: additionally loads the employee's `employee_payroll_config`
  row (`isMarried`, `childrenCount` — already persisted there since the old model's
  `b00c507` fix) and seeds them into the execution context, so Tunisia's new
  `ASSIETTE_IRPP` formula can reference them. Countries without a family-deduction
  concept simply don't reference these variables in their own strate-4 formula.
- **`ResultPersister.persist()`** (rewritten): computes `aggregateNet` and
  `aggregateEmployerCharges` from the rubriques actually evaluated this run, using the
  new `payer` column, instead of `STRATE_5`/the charge-bridge's `CHARGES_ER`:
  - `aggregateEmployeeCharges` = Σ(`COTISATION` rubriques where `payer='EMPLOYEE'`)
  - `aggregateEmployerCharges` = Σ(`COTISATION` rubriques where `payer='EMPLOYER'`)
  - `aggregateTaxes` (new, or reuse an existing field if one fits) = Σ(`TAXE` rubriques)
  - `aggregateNet` = `aggregateGross − aggregateEmployeeCharges − aggregateTaxes`
  - `loadedCost` = `aggregateGross + aggregateEmployerCharges`
  The charge-bridge call (`injectChargeVariables()`) is left in place and still runs —
  its output is simply no longer read by any of the above.
- **`SystemVariableAggregator.populateResult()`**: deleted (confirmed zero callers).
- **New-engine rubrique admin** (`EngineConfigAdminController`/
  `engine-rubriques-admin.component`, currently unreachable from the UI per the
  2026-10-22 audit but still a real write path): needs awareness of the new `payer`
  field so a future rubrique created through it isn't silently miscounted the same way
  TFP/FOPROLOS were — scope this as part of the same change since it's the same table.

## Testing

- **Acceptance bar (Tunisia)**: running the new engine with the same inputs as the
  already-validated old-model reference scenario (gross=462, CDI, no benefits, single,
  0 children) must produce `net` matching the old model's `415.40` within a small
  tolerance (propose ±0.01, matching the old engine's own convergence tolerance).
  `loadedCost` does **not** match the old model's `543.17` — that figure structurally
  excludes TFP and FOPROLOS, which have no old-model equivalent at all. The correct
  new-model `loadedCost` for this scenario is `543.17 + TFP(462×2%=9.24) +
  FOPROLOS(462×1%=4.62) = 557.03`; assert against that value instead.
- **Regression (Tunisia)**: `loadedCost` must equal `gross + CNSS_PATRONAL + TFP +
  FOPROLOS + AT_PATRONAL` exactly — the specific undercount bug 3 described.
- **Regression (both countries)**: `aggregateNet`/`loadedCost` must no longer read 0
  when `assiette_code` resolution exists; an employee/employer split check confirming
  `payer`-filtered sums don't mix sides.
- **Egypt**: structural check only — `INCOME_TAX` is non-zero and positive for a
  representative gross. Explicitly not a correctness assertion against any reference.
- **Manual**: re-run `POST /api/payroll/engine/run` for a real Tunisia employee via the
  actual UI ("Calcul de paie") and visually confirm "Résultats de paie" shows sane,
  internally-consistent numbers.
