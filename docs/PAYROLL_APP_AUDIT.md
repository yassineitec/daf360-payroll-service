# DAF360 Payroll App — Full Audit (Backend + Frontend + Database)
# Prepared 2026-10-22 for Claude Desktop, so it has accurate, verified context on the
# payroll app before any further work is discussed. Everything below was read directly
# from source code, live database schema, and git history — not inferred or guessed.
# Repos: daf360-payroll-service (backend, Spring Boot, port 8893, DB `DAF360_PAYROLL`),
# daf360-payroll-frontend (Angular, standalone components, port 4205, federated as
# remote "payroll" into the DAF360 shell under `/payroll`).

---

## 0. THE ONE THING TO UNDERSTAND FIRST: two parallel payroll engines

This codebase contains **two independent, only partially-bridged payroll calculation
engines**, built at different times, both still live. Nothing here is hypothetical —
this is the actual current state, confirmed by reading the real service/calculator
classes.

### "Old model" — `modules.parameterset` + `com.daf360.payroll.engine`
- Entities: `ParameterSet`, `PayrollRubrique` (table `payroll_rubriques_legacy` —
  literally renamed by migration V17 to free the name `payroll_rubriques` for the new
  model), `BenefitCatalogue`, `SocialChargeRate`.
- One `ParameterSet` row = one country + fiscal year + version, with a
  DRAFT→PENDING_FINANCE→ACTIVE→ARCHIVED approval workflow.
- Calculation engine: `PayrollSimulatorService` + `TopologicalEvaluator` +
  `ConvergenceEngine` + `IrppCalculatorService` + `FormulaEvaluatorService`.
- **Still the live engine behind**: individual/cohort Simulation, and Employee-Config's
  "Calculer le net" button. Not dead, not deprecated in practice — actively used today.

### "New model" — `modules.payroll`
- Entities: `PayrollCountry`, `PayrollParamSet`, `PayrollRubriqueDef`, `PayrollResult`,
  `PayrollForexSnapshot`.
- Calculation engine: `modules.payroll.orchestrator.PayrollOrchestrator` +
  `modules.payroll.calculator.*` (6 pluggable calculator strategies).
- This is the **real payroll-run** engine: `POST /api/payroll/engine/run` computes and
  **persists an immutable `PayrollResult`** for a specific employee + period. The old
  engine never does this — it only ever produces a what-if `SimulationResult` or a
  throwaway "calculate-net" preview.

### The two engines are bridged, not separate
`PayrollOrchestrator` (new engine), once it knows an employee's gross salary, calls
`injectChargeVariables()` — which loads the **old** model's `SocialChargeRate` rows for
the country's active (old-model) `ParameterSet`, runs them through the **old**
`TopologicalEvaluator`, and injects the resulting charge variables (`CNSS_EE`,
`CHARGES_EE`, `CHARGES_ER`, etc.) into the **new** engine's execution context. This is
best-effort — any failure here is silently swallowed. **Practical consequence: social
charges computation has not actually been migrated to the new model at all.** The new
engine borrows the old one for that one piece every single time it runs.

### A table-naming trap worth knowing about
`payroll_budget_lines` (F.04) and `payroll_forecast_outputs` (F.07) look like they
belong to the new model (modern naming, `payroll_` prefix) and a naive DB inspection
might assume they FK to `payroll_parameter_sets`. **They don't** — `parameter_set_id`
on both tables has no FK constraint at all, and the Java code that populates them
(`CalibrationFinanceService`) takes an **old-model** `ParameterSet` as its event
payload, triggered by old-model `ParameterSetService.activate()` publishing a
`ParameterSetActivatedEvent`. These two "finance output" tables are entirely downstream
of the **old** engine, despite their modern-sounding names.

### Which tables belong to which model
| Old model | New model | Shared / neither |
|---|---|---|
| `parameter_sets` | `payroll_countries` | `pays_ref`, `users_ref` (replicated from RH) |
| `payroll_rubriques_legacy` | `payroll_parameter_sets` | `employee_payroll_config`, `_history`, `employee_payroll_bonus` |
| `benefits_catalogue` | `payroll_rubriques` | `calibration_cycles`, `calibration_variances` (old-model calibration, own family) |
| `social_charge_rates` | `payroll_results` | `payroll_budget_lines`, `payroll_forecast_outputs` (populated FROM the old model, despite the name) |
| `simulation_results` | `payroll_forex_snapshots` | |
| `cohort_definitions` | `payroll_calibration_imports`, `payroll_calibration_lines` (new-model calibration, see §6) | |
| | `payroll_precision_kpi_history` | |
| | `payroll_calculation_sequences` (entity/repo exist, **no code reads or writes it** — likely unused/planned, unverified either way) | |

---

## 1. Backend module structure (`src/main/java/com/daf360/payroll/modules/`)

| Module | Covers |
|---|---|
| `admin` | Country engine setup, cross-module audit log, new-model engine param-set/rubrique admin writes. |
| `calibration` | Three distinct sub-features sharing one package — see §6. |
| `employeeconfig` | Per-employee payroll config (benefits, current salary) + one-off bonuses. Also the backend for `daf360-rh-frontend`'s "Rémunération" tab, which calls these same endpoints directly. |
| `parameterset` | The old payroll model end to end. |
| `payroll` | The new universal payroll-calculation engine end to end. |
| `ref` | Replicated `pays_ref`/`users_ref` from `daf360-rh-service`, refreshed every 15 min. |
| `salaryadvance` | Full salary-advance lifecycle: request, approval, disbursement, deduction reconciliation, per-country policy. |
| `simulation` | What-if individual/cohort simulation against the old model, plus PDF export. |

Outside `modules/`: `com.daf360.payroll.engine` (old calculation engine), `config`
(Spring/security/Flyway/RestTemplate config), `security` (JWT + permission catalog +
pays-scope), `web` (global exception handler).

---

## 2. Old engine algorithm (`PayrollSimulatorService` + `TopologicalEvaluator`)

Concept: 5 strata, net ↔ gross. Two entry points — `computeFromGross()` (one top-down
pass) and `computeFromNet()` (inverts via `ConvergenceEngine`: Newton-Raphson with
bisection fallback, tolerance/iterations from the active `ParameterSet`, default
tolerance 0.01 / 50 iterations).

**`TopologicalEvaluator.evaluate()`**, three phases:
1. **Charges**: filters `SocialChargeRate` by contract type, builds a dependency graph
   from each row's `formula_ee`/`formula_er` exp4j expressions (referencing other
   charges' `{CODE}_EE`/`{CODE}_ER`), topologically sorts (Kahn's algorithm), evaluates
   in order. No formula → falls back to `min(base, cap) × rate`.
2. **Aggregates**: `CHARGES_EE`/`CHARGES_ER` = sum of all evaluated charges, added to
   the shared variable context.
3. **Rubriques** ("pay components", `PayrollRubrique` rows): filtered by contract type,
   sorted by `displayOrder`; `FORMULE`-mode rubriques get their own topological sort so
   one rubrique formula can reference an earlier rubrique's result. Per-rubrique calc
   modes: `FIXE_MENSUEL` (flat amount), `FIXE_JOURNALIER` (`amount × joursTravailes/22`),
   `POURCENTAGE_BRUT` (`gross × rate`), `POURCENTAGE_CHARGES` (`totalBaseCharges × rate`),
   `POURCENTAGE_PLAFONNE` (`min(gross, capAmount) × rate`), `FORMULE` (arbitrary exp4j
   expression against the full context — a bad formula silently yields ZERO + a warning
   log, never a crash).

Rubriques are then split by direction (CREDIT/DEBIT) and
taxable/social-charge-subject flags; if any rubrique widens the social-charge base,
charges are **recomputed** on the new gross. `taxableBase = gross − employeeCharges +
taxableBenefits + taxableCredit`; `irpp` = progressive-bracket tax on that base
(`IrppCalculatorService`); `netInHand = taxableBase − irpp + nonTaxableBenefits +
nonTaxableCredit − totalDebit − employeeShareTotal`; `loadedCost = gross +
employerCharges + employerShareTotal`.

Charges and rubriques interact exactly once: rubrique formulas can read charge
results/aggregates; charges can never read rubrique results (charges always run
first).

---

## 3. New engine algorithm (`PayrollOrchestrator` + `modules.payroll.calculator.*`)

Triggered by `POST /api/payroll/engine/run` (`employeeId`, `paysId`, `periodYear`,
`periodMonth`, optional `contractTypeCode`/`joursOuvresMois`).

1. **`ContextLoader.load()`** — active `PayrollCountry` + its active `PayrollParamSet`,
   parses the `parameters` JSON column into an arbitrary key→value map, seeds the
   execution context with `JOURS_OUVRES`/`MOIS`/`ANNEE`.
2. **`SequenceValidator.validate()`** — rubriques sorted by `displayOrder`; each one's
   `assietteCode` (what it reads as its base) must already be resolvable — rejects
   forward references before computing anything.
3. Per `PayrollRubriqueDef`, **`CalculationDispatcher`** routes to one of 6 calculator
   beans by `modeCalcul`:
   - `TAUX_PCT` → `min(assiette, plafond) × taux/100`
   - `MONTANT_FIXE` → fixed amount from the parameters map
   - `BAREME_PROGRESSIF` → progressive-bracket tax on the assiette
   - `ANNUALISE_BAREME` → same brackets on `assiette × 12`, result ÷ 12 (typical use:
     monthly IRPP withholding derived from an annual scale)
   - `FORMULE` → Spring SpEL, context variables textually substituted
     (longest-name-first to avoid partial-token corruption)
   - `NEWTON_RAPHSON` → solves `formula(SELF) == target` via Newton-Raphson with
     central-difference derivative (max 100 iterations, tolerance 0.01) — e.g. deriving
     gross from a known net
   Then prorata (`× joursOuvresMois/22`) and a contract-type filter are applied.
4. **`SystemVariableAggregator`** re-sums computed rubriques into `STRATE_1..5` plus
   aliases (`BRUT`, `AVANTAGES`, `CHARGES_SAL`, `NET_IMPOSABLE`, `NET_A_PAYER`) after
   each rubrique, so later rubriques can reference them.
5. **Charge bridge** (§0): once BRUT is known, the **old** `TopologicalEvaluator` runs
   against the old model's active `SocialChargeRate`s and injects results into the new
   context — best-effort, failures swallowed.
6. **`ResultPersister`** does a single immutable INSERT of the full `PayrollResult`
   (strate1-5, aggregates, loaded cost, JSON `rubriqueDetails`, convergence flag) — no
   update path exists anywhere.
7. **`ForexSnapshotService`** fires fire-and-forget afterward: calls each URL in
   `PayrollCountry.forexApiSources` (admin-configured, no auth), persists a
   `PayrollForexSnapshot` row per source. Never fails the payroll run.

Note: the new engine's "strate 1-5" are **not** the same concept as the old engine's
5-strata ladder — they're configurable buckets a rubrique is assigned to (`strate`
field, `CK_rubrique_strate BETWEEN 1 AND 5`). A code comment admits strate 3
("employee charges") is used as an **employer**-charges proxy — a known simplification.

---

## 4. Simulation module

Not a real payroll run — a what-if calculation against the **old** model, persisted
only as an audit `simulation_results` row, never written back as a real payslip.
`SimulationController` (`/api/payroll/simulations`):
- `POST /individual` — one employee/candidate, NET_TO_BRUT or BRUT_TO_NET.
- `POST /cohort` — a batch under a persisted `CohortDefinition`, each row its own
  `SimulationResult` (`simulationType=COHORT`).
- `POST /cohort/aggregate` ("Cohort V2") — fetches the **live** active workforce from
  `daf360-rh-service` via `HrEmployeeClient.fetchActiveEmployees()`, filters by
  grade/discipline/contract type, runs the old engine per employee for current cost,
  re-runs after a salary modifier (% or absolute) to project cost impact — returns
  **aggregate KPIs only**, no individual salaries.
- `GET /{id}/export-pdf` — renders HTML, posts to the external pdf-service; falls back
  to raw HTML if that service is unreachable.

`HrEmployeeClient.findEmployeeByUserId()` hydrates an individual simulation's
contract/grade/discipline from the live HR profile when a `profileUserId` is supplied
(HR unavailability is non-fatal — falls back to whatever the request already had).

---

## 5. Employee payroll config — and the "Rémunération" tab connection

`employee_payroll_config` (one row per employee): country, contract type,
`selectedBenefitCodes` (JSON array of old-model `benefits_catalogue` codes), current
gross/net salary, audit fields. `employee_payroll_config_history` is an append-only
change log. `employee_payroll_bonus` is a **separate, simpler**, purely-record-keeping
table of one-off bonuses — explicitly documented in its own service's Javadoc as "not
fed into PayrollSimulatorService/TopologicalEvaluator" — it has zero computational
effect today.

**Confirmed two-frontend fact**: `daf360-payroll-frontend`'s "Configuration employé"
screen and `daf360-rh-frontend`'s "Rémunération" tab on an employee's profile are two
different UIs calling the **exact same** `GET`/`PUT /api/payroll/employee-configs/{id}`
— the RH frontend calls `daf360-payroll-service` **directly**, bypassing
`daf360-rh-service` entirely for this one feature.

"Calculer le net" (`POST .../calculate-net`) loads the employee's configured country's
active **old-model** `ParameterSet` and calls `PayrollSimulatorService.computeFromGross()`
directly — deliberately does **not** persist a `SimulationResult` row (that table is
reserved for real simulation-run history); this is a thin, non-audited preview used
only to fill the net field while editing.

---

## 6. Calibration module — three sub-features, very different states of completeness

1. **Old-model calibration cycle** (`calibration_cycles`/`calibration_variances`,
   `CalibrationCycleService`): backend complete. Opens a cycle for a country+period
   seeded with a predicted total loaded cost from the latest cohort simulation; user
   uploads a CSV of the external payroll provider's actual per-employee loaded cost
   (`PartnerPayrollParserService`); the service computes per-employee variance and a
   global variance %, auto-closing the cycle `CLOSED` or `REQUIRES_UPDATE` depending on
   whether variance exceeds the active `ParameterSet.calibrationThresholdPct` (default
   1%). **There is no manual close action and no reopen path.** The repository can
   query per-employee variance (`findByCycleId`) but **no endpoint exposes it** —
   only the cycle-level aggregate is returned.

2. **New-model calibration import** (`payroll_calibration_imports`/`_lines`,
   `payroll_precision_kpi_history`): backend **incomplete**. Open/list endpoints exist
   (`PayrollEngineController`), but `CalibrationImportService.recordActuals()` — the
   only method that would populate `payroll_calibration_lines` with real variances —
   has **zero callers anywhere in the codebase**. `KpiHistoryService.record()` is
   likewise unreachable; only its read counterpart (`history()`) is wired to a
   controller. **There is currently no way to submit real values into this feature via
   the API**, and the frontend confirms zero UI consumes any of its 4 service methods
   either (`openEngineCalibration`/`listEngineCalibrations`/`getCalibrationLines`/
   `getKpiHistory` on `PayrollEngineService` have no call sites outside that file).

3. **Budget/forecast outputs** (F.04/F.07, `payroll_budget_lines`/
   `payroll_forecast_outputs`): backend complete, see the table-naming trap in §0 — this
   fires automatically when an **old-model** `ParameterSet` becomes `ACTIVE`
   (`AFTER_COMMIT` event listener, so a failure here never rolls back the approval).
   F.04 produces `EMPLOYEE_NET`/`EMPLOYER_LOADED` lines (from the latest
   CLOSED/REQUIRES_UPDATE calibration cycle, or falling back to the latest COHORT
   simulation). F.07 produces MONTHLY/QUARTERLY/ANNUAL forecast rows from
   `EMPLOYER_LOADED` (×1/×3/×12). Read-only via `CalibrationController`.

### Current frontend reachability (verified directly against `main`, 2026-10-22 — do
### not trust any older internal doc claiming these are simply "commented out in
### app.routes.ts", that description is stale)
The sidebar nav and the top-level `app.routes.ts` guards for **Calibration**, **Budget**,
and **Cohort** are all fully live and correctly permission-gated — a user sees them,
clicks them, and the route resolves (no 403/404). But each module's own nested
`*.routes.ts` file routes `path: ''` to a shared `UnderDevelopmentComponent` placeholder
instead of the real component:
- `budget.routes.ts` → placeholder (real `BudgetComponent` fully built, unplugged)
- `calibration.routes.ts` → placeholder (real `CalibrationComponent` fully built,
  unplugged)
- `cohort.routes.ts` → placeholder (real `CohortComponent` fully built, unplugged)

All three real components are complete and correctly wired to real backend endpoints —
they're simply not reachable by routing today. Timeline (from git log): all three were
genuinely live as of commit `890b5e6` (2026-09-22); `58f3aaf` (2026-09-24) unplugged
calibration and cohort while building candidate-simulation; `54ac9a0` (2026-09-25)
unplugged budget too. Nothing since has reconnected any of them.
**`candidate-simulation` is unaffected** — it's fully live.

### A second, independent soft-disable: 5 of 6 admin sub-screens
Inside `parameter-sets.component.ts`'s admin-home card grid, 5 of 10 cards are
commented out in the source (`countries`, `engine-params`, `rubriques`, `benefits`,
`audit`) — deliberately, per the component's own doc comment ("Masquées pour
l'instant… code conservé"). Their `@switch` branches, templates, and backing
components (`payroll-countries-admin`, `engine-params-admin`,
`engine-rubriques-admin`, `benefits-catalogue-admin`, `payroll-audit-admin`) are fully
built and wired to real endpoints — but because the cards are absent (not merely
permission-filtered), even a hand-typed `?tab=countries` URL bounces back to the admin
home. Confirmed via git log this has been the state since these admin screens were
first built (commit `655220a`, 2026-09-29) — not a regression. **Only
`advance-rules-admin` is currently reachable** from the admin home UI.

---

## 7. Budget/forecast module (frontend)

`BudgetComponent` (currently unroutable, see §6): per country, shows the latest
period's `EMPLOYEE_NET`/`EMPLOYER_LOADED` budget lines as KPI cards, latest
MONTHLY/QUARTERLY/ANNUAL forecasts, and a history table of older periods. Calls
`getBudgetLines()`/`getForecastOutputs()` via `forkJoin`.

---

## 8. Cross-service integration (every outbound HTTP call, backend)

| Client | Calls | Target | Auth | Purpose |
|---|---|---|---|---|
| `HrEmployeeClient` | `GET /api/hr/internal/payroll-sync/employees[?paysId=\|/{userId}]` | `daf360-rh-service` | `X-Internal-Key` (`app.hr-internal-api-key`) | Employee salary/profile for simulations, cohort workforce, employee-config defaults. Fails soft. |
| `HrAdvanceClient` | `GET .../salary-advances/employees/{userId}`, `GET …?userIds=`, `POST .../notifications` | `daf360-rh-service` | same `X-Internal-Key` | Salary-advance eligibility (throws if RH down — "down" must never read as "not eligible"), batch name lookups (fails soft), decision notifications (fails soft). |
| `SimulationPdfService` | `POST /pdf/api/render` | `pdf-service` (the `pdf-service-master` project) | none found | Renders a simulation sheet to PDF; falls back to raw HTML if unreachable. |
| `ForexSnapshotService` | `GET {url}` per `PayrollCountry.forexApiSources` | admin-configured external FX APIs | none | Live FX rate snapshotting after each new-engine run, best-effort. |
| `ProfileSyncService` | `GET /api/hr/pays-for-sync`, `GET /api/hr/users-for-sync` | `daf360-rh-service` | none found | Scheduled every 15 min, replicates `pays`/`users` into local `pays_ref`/`users_ref`. |

**⚠️ Uncommitted local change at the time of this audit**: `AppProperties.java`,
`HrEmployeeClient.java`, and `application.yml` have real, uncommitted local edits that
fix a genuine pre-existing bug — `HrEmployeeClient` was calling
`/api/internal/payroll-sync/...` (missing the `/hr` segment) with a separate,
stale `X-Service-Key`/`hrServiceKey` secret that apparently never matched any real
RH-side route, meaning every cohort-aggregate call was silently getting a 401 and
reading as "zero employees in this cohort" rather than failing loudly. The fix unifies
onto the same `X-Internal-Key`/`hr-internal-api-key` scheme `HrAdvanceClient` already
uses correctly. **This fix exists only in the local working tree as of 2026-10-22 — it
has not been committed.** If you're reasoning about a fresh checkout, this bug is still
present there.

---

## 9. Permission catalog (`com.daf360.payroll.security.PermissionCatalog`)

| Code | Meaning |
|---|---|
| `PAYROLL_RUN_SIMULATION` | Run individual/cohort what-if simulations |
| `PAYROLL_VIEW_INDIVIDUAL` | View individual simulation history/detail |
| `PAYROLL_APPROVE_PARAMSET` | Create/approve old-model parameter sets + related admin |
| `PAYROLL_RUN_CALIBRATION` | Open/view old-model calibration cycles |
| `PAYROLL_EXPORT_BUDGET` | Read F.04/F.07 outputs |
| `PAYROLL_IMPORT_PARTNER` | Declared, **no `@PreAuthorize` reference found anywhere** — unused |
| `PAYROLL_VIEW_AGGREGATE` | View aggregate simulation/calibration/cohort results |
| `PAYROLL_APPROVE_PARAMSET_FAST_TRACK` | Fast-track Finance/HR approval, old + new model |
| `PAYROLL_VIEW_PARAMSET` | Read-only parameter-set/rubrique access |
| `PAYROLL_UPLOAD_ACTUAL` | Upload partner-payroll CSV to close a calibration cycle |
| `PAYROLL_SUPER_ADMIN` | Bypasses pays (country) isolation entirely |
| `PAYROLL_RUN_ENGINE` | Trigger a real new-engine payroll run |
| `PAYROLL_VIEW_RESULTS` | Read new-engine `PayrollResult` data |
| `PAYROLL_MANAGE_RUBRIQUES` | Create/edit new-engine rubrique definitions |
| `PAYROLL_MANAGE_COUNTRIES` | Configure `PayrollCountry` |
| `PAYROLL_IMPORT_CALIBRATION` | Open/list new-model calibration imports (the incomplete feature) |
| `PAYROLL_VIEW_BUDGET_AGGREGATE` | Country-Director-style aggregate-only budget access — explicitly meant to never see per-employee variance |
| `PAYROLL_VIEW_EMPLOYEE_CONFIG` / `PAYROLL_MANAGE_EMPLOYEE_CONFIG` | Read / edit an employee's payroll config, bonuses, "calculate net" |
| `PAYROLL_MANAGE_SALARY_ADVANCES` | Payroll's side of salary advances |
| `FACT_APPROVE_SALARY_ADVANCE` | **Not** a PAYROLL_* code — lives in the Finance (`daf360-facturation-service`) catalog; Finance approves, Payroll owns the data |

`ADMIN_PERMISSIONS = {PAYROLL_SUPER_ADMIN}` bypasses `PaysIsolationInterceptor`.

---

## 10. Auth / JWT

Same pattern as sibling DAF360 services: `JwtAuthFilter` tries, in order, `Authorization:
Bearer` header → `daf360_rh` cookie (HMAC) → `daf360_access` cookie (RSA) — **first
token that actually validates wins**, not just the first one present. `JwtService`
auto-detects RS256 vs HMAC from the unverified header. Claims: `sub`→principal,
`permissions`→authorities, `paysScopeAll`/`paysIds`→a per-request `ThreadLocal`
`PaysScopeContext` (cleared in a `finally`). `app.jwt-disabled=true` (dev-only) bypasses
auth entirely, granting every permission to principal `"1"`.

Country/entity isolation is separate from authentication: `PaysIsolationInterceptor`
checks the `paysId`/`pays` **request parameter** (not path variables or body fields —
those are checked manually in service code) against the caller's `PaysScope`, 403ing on
mismatch. `PAYROLL_SUPER_ADMIN` bypasses it. `SecurityConfig` itself permits all
requests at the filter-chain level — all real access control is `@PreAuthorize` +
a custom `PayrollPermissionEvaluator`.

---

## 11. Backend controllers

| Controller | Base path | Covers |
|---|---|---|
| `ParameterSetController` | `/api/payroll/parameter-sets` | Old-model CRUD + HR/Finance approval + charges/benefits/rubriques editing |
| `PayrollEngineController` | `/api/payroll/engine` | New-engine run, results, rubrique listing, new-model param-set workflow, new-model calibration (incomplete) |
| `SimulationController` | `/api/payroll/simulations` | Individual/cohort/cohort-aggregate simulation, history, PDF export |
| `CalibrationController` | `/api/payroll/calibration` | Old-model calibration cycle CRUD, CSV upload, F.04/F.07 reads |
| `EmployeePayrollConfigController` | `/api/payroll/employee-configs` | Per-employee config get/upsert/history, calculate-net |
| `EmployeePayrollBonusController` | `/api/payroll/employee-configs/{id}/bonuses` | Bonus list/create/delete |
| `SalaryAdvanceController` | `/api/payroll/salary-advances` | Employee self-service, finance decision queue, payroll ops |
| `PayrollAdminController` | `/api/payroll/admin` | List payroll countries, cross-module audit log |
| `EngineConfigAdminController` | `/api/payroll/admin/engine` | New-engine param-set draft + rubrique writes |
| `RefController` | `/api/payroll/ref` | Replicated pays/users + manual resync |

---

## 12. Ports / profiles / Docker

- Server port **8893** (`server.port`, confirmed in `application.yml` and `Dockerfile`).
- Only a `local` profile file exists (`application-local.yml`, git-ignored, dev-only:
  `jwt-disabled` toggle, verbose logging). No `dev`/`prod`/`docker` profile files —
  environment differences are env-var overrides of the same `application.yml` defaults.
- `AppProperties.hrApiBaseUrl` has a Java-field-level Docker-hostname default
  (`http://daf360-rh-backend:8888`) while `application.yml`'s own default is
  `http://localhost:8888` — whichever actually wins depends on `HR_API_BASE_URL` being
  set (YAML wins when present).

---

## 13. DATABASE SCHEMA — `DAF360_PAYROLL`, verified live (2026-10-22)

**Flyway is enabled** (`flyway.enabled: true`, `locations: classpath:db/migration`),
and `flyway_schema_history` has 17 real rows through **V24** (last:
`employee_payroll_config` gross-salary/bonuses enhancement, 2026-09-24). But
**`ddl-auto: none`**, AND commit `045304b` ("Remove obsolete SQL migration files…")
deleted **all 24** migration `.sql` files from the repo — `src/main/resources/db/
migration/` is now empty. **This means the repo currently has no way to bootstrap a
brand-new, empty `DAF360_PAYROLL` database from scratch.** Against the
already-provisioned database that exists today, this is harmless (Flyway just has
nothing new to apply) — but it's a real operational risk if this service ever needs a
fresh environment, and looks like an unintentional side effect of a cleanup commit
rather than a deliberate decision. The deleted files were recovered via
`git show 045304b^:<path>` for this audit and are not lost, just absent from HEAD.

### Live tables (23, excluding `flyway_schema_history`), with row counts as of this audit

| Table | Rows | Model | Notes |
|---|---|---|---|
| `pays_ref` | 194 | shared | replicated from RH |
| `users_ref` | 246 | shared | replicated from RH |
| `parameter_sets` | 2 | old | |
| `payroll_rubriques_legacy` | 11 | old | renamed from `payroll_rubriques` by V17 |
| `benefits_catalogue` | 7 | old | |
| `social_charge_rates` | 20 | old | |
| `simulation_results` | 22 | old | |
| `cohort_definitions` | 4 | old | |
| `calibration_cycles` | 0 | old (calibration) | |
| `calibration_variances` | 0 | old (calibration) | |
| `payroll_countries` | 2 | new | |
| `payroll_parameter_sets` | 2 | new | |
| `payroll_rubriques` | 13 | new | |
| `payroll_results` | 0 | new | **never run in this environment yet** |
| `payroll_forex_snapshots` | 0 | new | follows from `payroll_results` being empty |
| `payroll_calculation_sequences` | 2 | new | entity/repo exist, no code path found that reads/writes it |
| `payroll_calibration_imports` | 0 | new (calibration) | feature is backend-incomplete, see §6 |
| `payroll_calibration_lines` | 0 | new (calibration) | same |
| `payroll_precision_kpi_history` | 0 | new (calibration) | write path unreachable, see §6 |
| `payroll_budget_lines` | 10 | **old** (despite name) | see §0 table-naming trap |
| `payroll_forecast_outputs` | 6 | **old** (despite name) | same |
| `employee_payroll_config` | 1 | neither | |
| `employee_payroll_config_history` | 1 | neither | |
| `employee_payroll_bonus` | 2 | neither | purely record-keeping, no computational effect |

### Foreign keys (verified live)
```
benefits_catalogue.parameter_set_id          -> parameter_sets.id
calibration_cycles.parameter_set_id          -> parameter_sets.id
calibration_variances.cycle_id               -> calibration_cycles.id
cohort_definitions.parameter_set_id          -> parameter_sets.id
parameter_sets.previous_version_id           -> parameter_sets.id         (self-ref, versioning)
payroll_calculation_sequences.country_id     -> payroll_countries.id
payroll_calculation_sequences.parameter_set_id -> payroll_parameter_sets.id
payroll_calibration_imports.parameter_set_id -> payroll_parameter_sets.id
payroll_calibration_imports.country_id       -> payroll_countries.id
payroll_calibration_lines.import_id          -> payroll_calibration_imports.id
payroll_forex_snapshots.payroll_result_id    -> payroll_results.id
payroll_parameter_sets.country_id            -> payroll_countries.id
payroll_precision_kpi_history.country_id     -> payroll_countries.id
payroll_precision_kpi_history.import_id      -> payroll_calibration_imports.id
payroll_results.country_id                   -> payroll_countries.id
payroll_results.parameter_set_id             -> payroll_parameter_sets.id
payroll_rubriques.country_id                 -> payroll_countries.id
payroll_rubriques_legacy.parameter_set_id    -> parameter_sets.id
simulation_results.cohort_id                 -> cohort_definitions.id
simulation_results.parameter_set_id          -> parameter_sets.id
social_charge_rates.parameter_set_id         -> parameter_sets.id
```
**`payroll_budget_lines.parameter_set_id` and `payroll_forecast_outputs.parameter_set_id`
have NO foreign-key constraint at all** — confirmed in the recovered V15 migration — a
real schema gap, not an oversight in this audit.

Full column-level schema (298 columns across 23 tables) was captured live via
`INFORMATION_SCHEMA`/`sys.columns` during this audit and is available on request — not
reproduced in full here to keep this document readable; ask for the specific table if
you need exact column names/types/nullability.

---

## 14. FRONTEND — routes, nav, and the live-vs-reachable distinction

**Read §6 above first** — the single most important frontend fact is that a route
being correctly guarded and present in the nav does **not** mean its real screen is
reachable; three module-level route files redirect to a placeholder.

### Top-level routes (`app.routes.ts`), all live at the routing/guard layer
| Path | Component loaded | Permission | Reachability |
|---|---|---|---|
| `accueil` | `HomeComponent` | none | live |
| `simulator` | `SimulatorComponent` | `PAYROLL_RUN_SIMULATION` | live |
| `payslips` | `PayslipBatchComponent` | `RH_MANAGE_PAYSLIPS` (an RH code, not PAYROLL_*) | live |
| `employee-config` | list + `:userId` detail | `PAYROLL_VIEW_EMPLOYEE_CONFIG`/`MANAGE_` | live |
| `salary-advances` | `SalaryAdvancesComponent` | `PAYROLL_MANAGE_SALARY_ADVANCES` | live |
| `cohort` | real `CohortComponent` **unplugged** | `PAYROLL_RUN_SIMULATION` | **placeholder** |
| `calibration` | real `CalibrationComponent` **unplugged** | `PAYROLL_RUN_CALIBRATION` | **placeholder** |
| `admin` | `ParameterSetsComponent` | `PAYROLL_VIEW_PARAMSET` | live (internally tabbed, see §6) |
| `budget` | real `BudgetComponent` **unplugged** | `PAYROLL_VIEW_BUDGET_AGGREGATE`/… | **placeholder** |
| `engine-run` | `EngineRunComponent` | `PAYROLL_RUN_ENGINE` | live |
| `engine-results` | list + `:employeeId` detail | `PAYROLL_VIEW_RESULTS` | live |
| `candidate-simulation` | `CandidateSimulationComponent` | `PAYROLL_VIEW_RESULTS` | live |

`''` and `**` both resolve via `payrollLandingGuard`, which derives the right landing
page from `PAYROLL_NAV_DEFS` order and live permissions, rather than a hardcoded
redirect — stale bookmarks (e.g. an old `/payroll/budget` link) resolve gracefully
instead of 404ing.

### Nav (`payroll-nav.ts`) — single source of truth both files "must agree" with
Sidebar order: Accueil → Simulation (group: Simulateur, Cohorte, Simulations candidats)
→ Avances sur salaire → Calcul de la paie/moteur → Résultats du moteur → Bulletins de
paie → Budget → Calibration → Config. des collaborateurs (hidden from sidebar, opened
only via its Administration card) → Administration. None of this is commented out.

### Feature-by-feature (live ones; see §6/§7 for the unplugged ones)

- **Simulator** (individual, old model): 3-step wizard — country + candidate/employee
  → direction (NET_TO_BRUT/BRUT_TO_NET) + amount → benefit selection. Renders a
  5-strata loaded-cost breakdown. Calls `runIndividualSimulation`,
  `getSimulationHistory`, `downloadSimulationPdf`.
- **Candidate simulations**: lists candidates with a submitted compensation
  simulation, drills into per-candidate negotiation rounds (PENDING/APPROVED/REJECTED).
  **Calls `daf360-rh-service`'s cost-approvals API, not the payroll backend** — this is
  HR's candidate-offer workflow, not the payroll engine.
- **Parameter Sets admin (old model)**, `/payroll/admin`, a single tabbed component
  (`ParameterSetsComponent`, 1688 lines) over a card grid: always-reachable "Créer"
  and "Jeux existants" tabs (parameter-set CRUD + HR/Finance approval workflow), plus
  the 5 unreachable admin sub-screens from §6 (countries, new-engine params,
  new-engine rubriques, old-model benefits catalogue, audit log) and the 1 reachable
  one (`advance-rules-admin`, also reachable as the "Règles" tab of Salary Advances
  itself).
- **Employee Config**: directory screen (server-side search against RH) → per-employee
  detail (country/contract/benefits/salary + bonuses tab + "Calculer le net").
  Confirmed, in the component's own doc comment, as the twin of `daf360-rh-frontend`'s
  Rémunération tab.
- **Salary Advances**: 4 tabs — À verser (disburse, creates repayment schedule),
  Retenues (one month's deductions, export CSV, mark deducted/skip/waive), Suivi (every
  advance + schedule), Règles (per-country policy, same screen as `advance-rules-admin`).
- **Engine run**: pick employee/period/contract/working-days, run the real new-model
  engine, see the strata/rubrique breakdown. Keeps a `localStorage`-only "recent
  presets" strip (no backend endpoint for presets exists).
- **Engine results**: directory → per-employee history of real engine-calculated
  `PayrollResult` rows.
- **Payslips**: upload one PDF of a month's payslips; `daf360-rh-service` splits it,
  matches pages to employees by matricule, files to SharePoint. Client-side
  `localStorage` history log only, no backend audit trail for this specific log.

### State management (brand new, 2 different implementations of the same idea)
No NgRx feature state for payroll (NgRx is used only for the shared cross-remote
`UserStore`). Instead, since **2026-10-14**, plain signal-based singleton services that
remember filters/sort/page across a drill-in-and-back navigation:
- A reusable helper, `admin-section-state.ts` (`AdminSectionStateService` +
  `rememberAdminState()`), used across `ParameterSetsComponent` and all 6 admin
  sub-components.
- Two independent, near-duplicate hand-rolled versions —
  `employee-config-list-state.service.ts` and `engine-results-list-state.service.ts` —
  that reimplement the same idea without using the shared helper.
This pattern is actively spreading through the app but has not converged on one
implementation. A separate, unrelated `sessionStorage` mechanism
(`rememberEmployee`/`recallEmployee`) caches employee identity for detail-page
banners — not filter state, don't confuse the two.

### i18n
Only **French and English** (no Arabic). Both files are 1379 leaf keys each, verified
programmatically as a perfect match — zero keys in one missing from the other. A
cross-check of every literal `PAYROLL.*` key referenced in code against the catalogs
found no genuine stale/missing key (37 false positives, all either dynamic
`${...}`-interpolated keys or non-key namespace references). Translations are embedded
directly in the JS bundle (not fetched over HTTP) specifically because this remote
doesn't own its own origin when federated into the shell — an HTTP i18n loader would
404 against the shell's own assets.

### Federation
Port **4205** (confirmed 3 ways: `package.json`, `angular.json`, `federation.manifest.json`).
Native Federation remote name `'payroll'`, exposes one module (`./Routes`). All
`@angular/*` deps pinned to an **exact** version (`21.2.18`, no caret) — the repo's own
history shows a real NG0203 dependency-injection bug from a caret-range patch-version
drift across federated remotes; this is why every DAF360 frontend now pins exact
Angular versions as a documented, hard-learned rule. `@khalilrebhiitec/daf360` just
bumped to `^5.5.0` in the most recent pull.

### Recent changes (the 5 commits just pulled, 2026-10-02 → 2026-10-22)
1. `f1cc708` — i18n additions + parameter-sets charges-editor UI polish.
2. `5bf3300` — dependency bump only (`@khalilrebhiitec/daf360` → ^5.4.0).
3. `c27693a` — horizontal UX sweep: a shared `searchTableRows()` utility wired into 29
   files across nearly every module, giving every list/table screen the same
   `daf-search-toolbar` interaction.
4. `84680a6` — the filter-persistence feature described above, landed across 12 files.
5. `ee25736` — dependency bump only (`@khalilrebhiitec/daf360` → ^5.5.0).
None of these five touch the unplugged-route or hidden-card situations from §6 — those
were set earlier (2026-09-22 through 2026-09-29) and remain unchanged today.

---

## 15. Summary of real, verified gaps (for anyone deciding what to work on next)

1. **Migration files deleted from the repo** (§13) — no way to bootstrap a fresh
   `DAF360_PAYROLL` database from scratch today. Likely unintentional.
2. **New-model calibration import is backend-dead-ended** (§6.2) — `recordActuals()`
   and `KpiHistoryService.record()` have zero callers; no way to submit real data via
   the API despite open/list endpoints existing.
3. **Calibration, Budget, and Cohort screens are fully built but unroutable** (§6/§14)
   — real components, real backend wiring, just routed to a placeholder since
   2026-09-24/25. Reconnecting them is a 3-line routing change each, not a rebuild.
4. **5 of 6 admin sub-screens are fully built but uncardable** (§6) — same situation,
   different mechanism (commented-out card array entries, not route placeholders).
5. **An uncommitted local backend fix** (§8) corrects a real, previously-silent 401
   bug in `HrEmployeeClient` (wrong endpoint path + wrong auth secret) that made every
   cohort-aggregate call since this client was written effectively always return "zero
   employees," never erroring loudly. Needs committing.
6. `payroll_budget_lines`/`payroll_forecast_outputs` have no FK constraint on
   `parameter_set_id` at all (§13) — a real schema gap.
7. `PermissionCatalog.PAYROLL_IMPORT_PARTNER` is declared but never checked anywhere.
8. `payroll_results`/`payroll_forex_snapshots` are both empty in this environment —
   the new engine has apparently never been run against real data here yet.

---

## 16. UPDATE (2026-10-23) — events since this audit was written

**The real `HrEmployeeClient` bug from §8/§15.5 was fixed and committed.** The
uncommitted local fix this audit originally flagged (wrong URL path, wrong auth
secret) has since been committed — if you're reasoning about a checkout from after
2026-10-23, that bug is gone.

**A real, separate bug was found and fixed in the Payroll Simulator's Grade/Discipline
dropdowns.** `daf360-payroll-frontend`'s `HrRefApiService.listGrades()`/
`listDisciplines()` were calling `GET /api/hr/lists/GRADE`/`DISCIPLINE` — the
generic `ConfigurableListController` system in daf360-rh-service, which has **never**
had `GRADE`/`DISCIPLINE` list types defined (confirmed empty on both localhost and
192.168.1.233). The dropdowns were therefore always empty for every country, not a
data gap specific to any one pays. The **real** grade/discipline data lives in
completely separate, dedicated `grades`/`disciplines` tables in `daf360_hr` (confirmed
real, populated data: Tunisia has 61 grades + 24 disciplines, Egypt has 60 + 24),
exposed by a different controller entirely: `GET /api/hr/ref/grades`/`/disciplines`
(param name `paysId`, not `pays`) — the same endpoint `EmployeeProfileController` and
the rest of the RH app already use. Fixed by repointing `HrRefApiService` at the
correct endpoint; not yet committed as of this writing.

**A separate, real SIVP/CIVP correction was made to the Tunisia 2026 parameter-set
work** (see `src/main/resources/db/migration/V25__tunisia_parameter_set_2026.sql` in
this repo): the questionnaire data this migration was built from used "SIVP" as a
label, which turned out to be the OLD name for the already-existing `CIVP` contract
type, not a separate one. An earlier draft of this work had (incorrectly) added SIVP
as a brand-new contract type across 3 tables in 2 databases (`daf360_hr`'s
`contract_types`/`contract_type_config`, and `DAF360_PAYROLL`'s `social_charge_rates`/
`employee_payroll_config` CHECK constraints) — all of that was found and fully
reverted the same day. The CORRECT, final state: `CIVP`'s own `CNSS` rate in the 2026
Tunisia parameter set was changed to full exemption (0%/0%, previously a partial 3%
employer-only rate), and no `SIVP` contract type exists anywhere in either database.
**Lesson for future work on this area**: if you ever see "SIVP" mentioned in a Tunisia
payroll context again, treat it as a synonym for `CIVP`, not a distinct status, unless
explicitly told otherwise.

**Database environment drift found (and partially fixed) while investigating the
above**: `daf360-rh-service`'s own `contract_types` table (V102, the real per-country
reference list — distinct from `contract_type_config`, a separate policy-fields
table) had never been applied on 192.168.1.233 at all (table didn't exist), while
localhost had it fully seeded. Backfilled via a new migration,
`daf360-rh-service/src/main/resources/db/seed/V103__backfill_contract_types_baseline.sql`.
Conversely, `contract_type_config` was fully seeded on 192.168.1.233 but had **zero
rows for any country** on localhost — this one was not backfilled (out of scope at
the time), so localhost's `contract_type_config` may still be effectively empty.

**Tunisia 2026 parameter set status**: exists and is correct on **localhost**
(`id=10008`, `status='DRAFT'`) — still not activated through the application's
HR→Finance approval workflow, so the live engine is still using the 2025 parameter
set (`id=9`, `status='ACTIVE'`) for every real calculation as of this writing (see
§0's `loadActiveEntity()`/`status='ACTIVE'`-only lookup — `fiscal_year` plays no role
in which configuration is actually used). **`V25` has not yet been run on
192.168.1.233 at all** — that server still only has the 2025 configuration.
