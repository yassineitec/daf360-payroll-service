# Employee Config — Currency-Aware Gross/Net Salary Entry

## Problem

`/payroll/employee-config/:userId` (payroll app) and `daf360-rh-frontend`'s "Rémunération"
tab (same employee profile, same backend) both let staff set an employee's gross/net
salary, but the gross field only accepts the country's working currency (EUR for
Tunisia) even though real salary figures are often known in the local currency (TND).
Same root problem the Simulator had — already fixed this session
(`2026-11-06-simulator-currency-entry-design.md`) — these are the second and third entry
points found needing the same fix.

## Out of scope

- The "Primes exceptionnelles" bonus currency picker (already exists on the payroll
  employee-config page, intentionally stores raw unconverted amounts forever — bonuses
  don't feed `PayrollSimulatorService`, so there's nothing to convert). Not touched.
- Any change to `PayrollSimulatorService` or the engine itself.
- Egypt/other-country rate validation — unrelated.
- Server-side recomputation of net on save. Confirmed by investigation: `currentNetSalary`
  is accepted raw from the frontend and never recomputed server-side today — that's
  pre-existing behavior, unrelated to currency, and stays as-is. The frontend still
  computes net via `calculate-net` (now currency-aware) and sends it through on save.

## Architecture

Reuses the Simulator's already-approved pattern exactly, on two more entry points
against the same backend rows:

```
daf360-payroll-frontend                  daf360-rh-frontend
  employee-config.component                remuneration-section.component
  <daf-select> currency picker             native <select> currency picker
         \                                        /
          \                                      /
           v                                    v
        PUT/POST /api/payroll/employee-configs/{id}[/calculate-net]
                     + inputCurrency
                              |
                              v
              EmployeePayrollConfigService
                 FxEntryConversionService.convert(amount, inputCurrency, workingCurrency)
                 (already generic, built for the Simulator — reused as-is)
                              |
                              v
              PayrollSimulatorService.computeFromGross()   <- unchanged, sees only
              employee_payroll_config.current_gross_salary    the converted amount
                 + input_currency, input_amount_original       (new columns, nullable)
```

`GET /api/payroll/ref/fx-rates` (already built for the Simulator) is reused as-is by
both frontends — its `@PreAuthorize` is widened to also allow the employee-config
permissions (`VIEW_EMPLOYEE_CONFIG`/`MANAGE_EMPLOYEE_CONFIG`), which it currently
excludes (confirmed: would 403 today for a user who can edit employee configs but
lacks simulator permissions).

## Data model changes

**`daf360-sql` (`db-payroll`)**: new columns, nullable, on both the live config row and
its history log (so the audit trail still shows what was actually typed, not just the
converted EUR value):
- `employee_payroll_config`: `input_currency NVARCHAR(3) NULL`, `input_amount_original DECIMAL(18,4) NULL`.
- `employee_payroll_config_history`: same two columns.

## Backend changes (`daf360-payroll-service`)

- `EmployeePayrollConfig`/`EmployeePayrollConfigHistory` entities gain `inputCurrency`/
  `inputAmountOriginal` fields, mirroring `SimulationResult`'s existing pattern.
- `UpsertEmployeePayrollConfigRequest` and the calculate-net request both gain an
  optional `inputCurrency` (exact DTO name/shape to confirm by reading the real current
  code when the plan is written — the calculate-net endpoint currently takes a raw
  gross value, not necessarily its own named DTO).
- `EmployeePayrollConfigService`:
  - `calculateNet()`: when `inputCurrency` is present and differs from the employee's
    country's working currency, converts the raw gross via `FxEntryConversionService`
    before calling `PayrollSimulatorService.computeFromGross()` — same as
    `IndividualSimulationService` already does for the Simulator.
  - `upsert()`: same conversion for `currentGrossSalary` before persisting. Persists
    `inputCurrency`/`inputAmountOriginal` (null when unchanged from the working
    currency — same "no redundant data for the common case" convention as
    `simulation_results`). Writes the same two fields into the history row it creates
    on every save.
- `RefController`'s `/fx-rates` endpoint: widen `@PreAuthorize` to add
  `VIEW_EMPLOYEE_CONFIG`/`MANAGE_EMPLOYEE_CONFIG` alongside the existing
  `VIEW_PARAMSET`/`RUN_SIMULATION`.

## Frontend changes

**`daf360-payroll-frontend`** (`employee-config.component.ts`/`.html`):
- Currency `<daf-select>` next to "SALAIRE BRUT", working currency first then every
  other currency with a cached rate — same picker UX as the Simulator's, reusing
  `PayrollApiService.getFxRates()` (already exists, already used by the Simulator).
- `onGrossSalaryChange()`'s `calculate-net` call, and `save()`'s `upsert` call, both
  send `inputCurrency`.
- On load, restores `inputCurrency` + the original typed amount when present (else
  defaults to the working currency + the stored converted amount) — same prefill
  fidelity as the Simulator's "Modifier"/"Dupliquer".
- New i18n keys under this repo's existing `PAYROLL.*` namespace
  (`public/i18n/fr.json`/`en.json`).

**`daf360-rh-frontend`** (`remuneration-section.component.ts`):
- Currency picker using this repo's **own existing convention**: a plain native
  `<select>` + `[ngModel]`, matching the bonus-currency picker already on this same
  page — **not** `<daf-select>`, which this component doesn't import or use anywhere.
- `RemunerationService` gains `getFxRates(): Observable<FxRateDto[]>`, mirroring its
  existing `getBenefits()` cross-service call to `payroll-service` (an established
  pattern in this service already, not a new kind of call).
- Same wiring as the payroll frontend: `inputCurrency` sent on calculate-net and
  upsert, restored on load.
- New i18n keys under this repo's existing flat `PROFILES.FIELDS.*` namespace
  (`public/assets/i18n/fr.json`/`en.json`).

## Testing

- Backend: unit tests on the new conversion step in `EmployeePayrollConfigService`
  (TND entry round-trips correctly through to the stored EUR `currentGrossSalary`,
  same style as `IndividualSimulationServiceTest`), and a test confirming the history
  row captures `inputCurrency`/`inputAmountOriginal` correctly on save.
- Manual: live round-trip on both screens for the same employee — enter a TND gross on
  the payroll employee-config page, confirm the computed net and saved record are
  correct, then open the same employee's Rémunération tab in the RH app and confirm it
  shows the same, currency-aware data consistently (both screens read/write the exact
  same backend row).
