# Cohort simulation: country scoping, role visibility, honest failures, salary what-if

**Draft. Do not merge before the frontend PR (`daf360-payroll-frontend` · `feature/cohort-reactivate`) is reviewed.**

## Summary

Hardens the payroll cohort simulation and adds a salary what-if:

- **Country scoping on every request body.** Payroll endpoints that receive `paysId` in a JSON body no longer accept a country outside the caller's role scope. Until now only `?paysId=` in the URL was checked.
- **Role visibility.**
  - `PAYROLL_VIEW_INDIVIDUAL` (or super-admin) sees exact figures and per-employee rows.
  - Everyone else sees totals only, and nothing at all about amounts for a group of fewer than 5 employees.
- **Honest failures.** If the RH service is down, the response is 503 instead of zeros. Invalid input gets 400 instead of 500. Employees without a salary, whose computation failed or that did not converge are counted and never summed.
- **Engine parity with the individual simulator:** family status, each employee's own benefit selection, the configured gross, 22 working days (now a setting).
- **FX** from the synced `payroll_fx_rates` table. A rate of exactly 1.0 between two different currencies is never used.
- **Salary what-if.** A change as a PERCENT (−50 to +200) or an AMOUNT, applied to the NET or the GROSS salary, for a group or for one employee (new endpoint).
- **Two warnings**, both additive:
  - `benefitsNoneSelected` for configurations that select no benefit;
  - `coverage`, the share of the group actually in the totals, with `LOW_COVERAGE` below `app.cohort.min-coverage` (0.90).

The full decisions and API contract are in `daf360-payroll-frontend/docs/COHORT_DECISIONS.md`. The evidence is in `COHORT_HARDENING_REPORT.md` and `COHORT_PREMERGE_REPORT.md` in the same folder.

## API and permission changes

| Endpoint | Before | After |
|---|---|---|
| Every endpoint whose body has `paysId`: `POST /simulations/individual`, `/simulations/cohort`, `/simulations/cohort/aggregate`, `/engine/run`, `POST /parameter-sets`, `PUT /employee-configs/{id}`, `POST /employee-configs/{id}/simulate`, `/calculate-net`, `POST /salary-changes/employee/{id}`, `PUT /salary-advances/policies/{paysId}`, `POST/PUT /admin/countries` | any country accepted | the body's country must be in the caller's scope (super-admin bypasses), otherwise **403**. `/api/payroll/internal/**` is excluded |
| `POST /simulations/cohort/aggregate` | `RUN_SIMULATION` | `RUN_SIMULATION`, `VIEW_AGGREGATE`, `VIEW_INDIVIDUAL` or super-admin; below k = 5 the amounts are `null` for non-individual roles |
| `POST /simulations/cohort` | `RUN_SIMULATION` | `RUN_SIMULATION` **and** (`VIEW_INDIVIDUAL` or super-admin) |
| `GET /simulations/cohort/{id}` | `VIEW_INDIVIDUAL` or `VIEW_AGGREGATE`, no country check | `VIEW_INDIVIDUAL` or super-admin, the cohort's country in scope; an unknown id is 404 (was `[]`) |
| `GET /simulations/{id}`, `/{id}/export-pdf`, `/{id}/export.xlsx` | unchanged permission | a stored COHORT row also needs `VIEW_INDIVIDUAL` |
| `GET /simulations/individual/history` | unchanged | COHORT rows only for `VIEW_INDIVIDUAL` |
| `POST /simulations/cohort/what-if/employee` | — | **new**: `VIEW_INDIVIDUAL` or super-admin |
| Any endpoint, invalid or missing field, non-numeric path or query value, unknown JSON field on the cohort requests | 500 (or ignored) | **400** with the message in `detail` |
| Cohort endpoints when RH is unreachable or refuses the key | 200 with zeros | **503** "Service RH indisponible" |

New response fields on `/cohort/aggregate` are additive; the 13 original fields keep their names and meaning:

- visibility, suppression, counts, FX details, change type and base, averages;
- `messages[]`;
- `employees[]` (individual view only);
- `benefitsNoneSelected`, `coverage`, `minCoverage`.

## Behaviour changes users will notice

- **403 for a country outside the user's scope**, on every page that sends the country in the body. This includes the HR candidate simulation for a candidate in another country, the payroll simulator, Rémunération saves and simulations, and the engine run.
- **`POST /cohort` and `GET /cohort/{id}` now need `PAYROLL_VIEW_INDIVIDUAL`.**
- **Stored COHORT simulations are hidden from the history** and from the detail and export endpoints for roles without the individual view.
- **503 instead of zeros** when the RH service is down: the page shows an error instead of a cost of 0.
- **`null` instead of 0** for any amount that is hidden (small group), cannot be computed honestly (nothing converged) or has no trustworthy FX rate.
- **The Tunisia figure changes**: the current monthly cost goes from 296,447 to about **325,271 TND** (+9.7 %). The main cause is that all 121 Tunisian payroll configurations select no benefit (NULL list), so a higher gross is needed for the same net. The configured gross and the family status add smaller effects.
  - **In EUR it goes from 296,447 to about 97,581**, because the stored TND→EUR rate of 1.000 is no longer used; the synced rate is 0.300.
  - The NULL-list case is now visible as the `BENEFITS_NONE_SELECTED` warning. What NULL should mean is a business decision still open with HR.
- **Egypt totals are partial**: 21 of 70 employees are in the totals (48 do not converge, 1 has no salary). The cause is the active parameter set 10, which holds EGP-sized rubriques in a USD payroll. It is reported as `LOW_COVERAGE`, and the page dims the figures until the user acknowledges.

## Test evidence

- `mvn clean test` on a clean clone: **243 tests, 0 failures**. The baseline before this work was 185.
- Live regression matrix (localhost): 848 calls, run against this branch and against `origin/main`. Every difference is an intended one listed above, and nothing legitimate is blocked. The details are in `COHORT_PREMERGE_REPORT.md`:
  - out-of-scope → 403;
  - 500 → 400 on validation;
  - the new endpoint;
  - the documented aggregate-role restrictions.
- The HR candidate simulation gives a field-by-field identical response on both builds (5 variants). The Rémunération tab calls are identical too.
- The internal endpoints are unaffected:
  - with the key, 200;
  - without a key or with a wrong key, 403.
- All payroll pages load with a scoped user and with a multi-country user, with zero 4xx/5xx.

## Schema and data

**No schema change. No data change.** No parameter set, benefit or rate was modified.

## Deployment notes

- Nothing to migrate.
- New optional settings, all with defaults:

  | Setting | Default |
  |---|---|
  | `app.cohort.min-group-size` | 5 |
  | `app.cohort.working-days` | 22 |
  | `app.cohort.gross-consistency-tolerance` | 0.05 |
  | `app.cohort.min-coverage` | 0.90 |

- **Set a non-default `INTERNAL_API_KEY` in every environment.** The rh, facturation and payroll backends all fall back to the same public placeholder when the variable is missing. None of the local compose files passes it to those three services. It must be the **same value** in all three.
- Deploy together with the frontend PR. The old frontend keeps working against this backend (backward-compatible body and fields), but it does not show the new warnings.

## Explicitly not done

- Protection against **differencing overlapping queries**. Two aggregate requests whose groups differ by one person can still reveal that person's cost to an aggregate-only role.
- Protection against **AMOUNT probing**. The "salary must stay > 0" rejection can be used to bracket a salary.
- An **access-log table**. Each run writes a `[cohort-access]` log line with counts only. The proposed table is in `COHORT_DECISIONS.md`; it is not created because there is no schema change.
- **Static FX on other screens**. Only the cohort uses the synced rate; other screens still read `payroll_countries`.
- Any change to the meaning of a NULL benefit list, the Egypt parameter set, or the Tunisian drafts 10008 and 20007.

## Manual test list (test platform)

1. **A user scoped to one country** (e.g. Tunisia only):
   - open Simulation → Simulateur manuel, compute a Tunisian simulation: it works;
   - in Rémunération, simulate and save for a Tunisian employee: it works;
   - if the role allows choosing another country, picking Egypt in the simulator gives an "Accès refusé" message (403).
2. **A multi-country user** (role with a LIST scope, e.g. Tunisia + Egypt):
   - repeat step 1 in both countries: both work;
   - a third country is refused.
3. **The candidate simulation from the HR page**:
   - on a candidate's offer, run the salary simulation for a Tunisian candidate: same figures as before the deployment;
   - a candidate in a country outside the HR user's scope is refused.
4. **The cohort page with a small group**:
   - as a role with `PAYROLL_VIEW_AGGREGATE` but not `PAYROLL_VIEW_INDIVIDUAL`, pick a grade with fewer than 5 people: counts only, no amount;
   - as a role with the individual view: amounts and the per-employee table;
   - Egypt, whole country: the "21 sur 70 employés inclus" line, the warning banner, and dimmed figures until "J'ai compris, afficher quand même" is ticked.
5. Stop the RH service briefly (if allowed) and run the cohort: an error message, not a cost of 0.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
