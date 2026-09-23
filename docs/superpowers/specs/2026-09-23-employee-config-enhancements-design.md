# Employee Payroll Config Enhancements — Design

## Goal

Extend the existing employee payroll configuration feature (shared by `daf360-payroll-frontend`'s "Configuration employé" page and `daf360-rh-frontend`'s profile-detail "Rémunération" tab) with three additions requested together because they all touch the same two screens and the same underlying entity:

1. A selectable employee list on the Payroll config page, alongside the existing search picker.
2. A "Salaire brut" (gross salary) field with an on-demand "Calculer le net" action that derives net salary from gross using the existing payroll simulation engine.
3. A per-employee list of "primes exceptionnelles" (one-off bonuses), editable from both screens.

## Current State (confirmed by code reading, 2026-09-23)

- `EmployeePayrollConfig` (table `employee_payroll_config`, entity/DTO/history all in `daf360-payroll-service`) currently holds: `profileUserId`, `paysId`, `contractType`, `selectedBenefitCodes` (JSON), `currentNetSalary`, plus audit fields. No gross salary, no bonus concept anywhere in either backend.
- `daf360-payroll-frontend`'s `employee-config.component.ts` edits one employee at a time, selected via `<app-employee-select>` (a search-as-you-type picker backed by `HrProfileService.searchEmployees(search, paysId)`, which returns a paginated `EmployeeListItem[]`).
- `daf360-rh-frontend`'s `remuneration-section.component.ts` is the RH-side twin: same DTO, same two endpoints (`GET`/`PUT /api/payroll/employee-configs/{profileUserId}`), called directly against `daf360-payroll-service` (bypassing `daf360-rh-service`).
- `daf360-payroll-service` already has a full bidirectional gross/net simulation engine (`PayrollSimulatorService.computeFromGross()` / `.computeFromNet()`, built on `TopologicalEvaluator`), exposed today via `POST /api/payroll/simulations/individual` (permission `PAYROLL_RUN_SIMULATION`), which persists a `SimulationResult` audit row per call.
- No "prime"/"bonus"/"exceptionnel" concept exists for an individual employee in either backend. The only adjacent concept, `payroll_rubriques.nature = 'PRIME'`, is a recurring, country/contract-type-wide line item configured per parameter set — not a one-off per-employee event, and out of scope here.

## 1. Employee List (Payroll config page)

**No backend changes.** The existing `HrProfileService.searchEmployees(search, paysId)` call already returns everything needed (`EmployeeListItem[]`, paginated).

Add a table below the current form, sourced from the same service call, scoped by the same `paysId` filter the page already tracks. Clicking a row calls the exact same `onEmployeeSelected(userId)` method the search picker already calls — the list is a second way to reach the same selection, not a new selection path. The search box stays for fast lookup in a long list.

## 2. Salaire Brut + Calculate

**Data model** (new migration `V24__employee_payroll_config_gross_salary.sql`):
- `employee_payroll_config.current_gross_salary` — nullable `DECIMAL(18,3)`, alongside the existing `current_net_salary`.
- `employee_payroll_config_history.current_gross_salary` — same, so history rows capture both values at each change, same as net today.

**DTOs**: `EmployeePayrollConfigDto`, `UpsertEmployeePayrollConfigRequest`, `EmployeePayrollConfigHistoryDto`, and their frontend TS mirrors (in both frontends) all gain `currentGrossSalary: BigDecimal | number | null`, following the exact pattern `currentNetSalary` already uses in each file — same nullability, same validation (none required; like net, it's optional).

**New endpoint**: `POST /api/payroll/employee-configs/{profileUserId}/calculate-net`
- Request body: `{ grossSalary: BigDecimal }`.
- Permission: `PAYROLL_MANAGE_EMPLOYEE_CONFIG` (the same permission that already guards editing this screen) — deliberately **not** `PAYROLL_RUN_SIMULATION`, so editing this screen never requires a second, unrelated permission grant.
- Behavior: hydrates `contractType`/country/etc. for `profileUserId` the same way `IndividualSimulationService.simulate()` already does (reusing its existing hydration helper), then calls `PayrollSimulatorService.computeFromGross(...)` directly and returns `{ netInHand: BigDecimal }`.
- Deliberately does **not** write a `SimulationResult` row. That table is real simulation-run history; routine "calculate net while editing an employee's config" clicks would otherwise pollute it. This is a thin, non-persisting read.
- Response DTO: `CalculateNetResponse(BigDecimal netInHand)`.

**Frontend** (both `employee-config.component.ts` and `remuneration-section.component.ts`): a new "Salaire brut" numeric input next to the existing net field, plus a "Calculer le net" button. Clicking it calls the new endpoint with the current gross value and writes the result into the net field's form control — which remains freely editable afterward, exactly like today. Both fields save together on the existing "Enregistrer" action, unchanged.

## 3. Primes Exceptionnelles

**Data model** (same `V24` migration as the gross-salary column above — one migration for this whole feature): new table `employee_payroll_bonus`:

```sql
CREATE TABLE employee_payroll_bonus (
    id                BIGINT IDENTITY(1,1) PRIMARY KEY,
    profile_user_id   BIGINT NOT NULL,
    amount            DECIMAL(18,3) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    period_month      TINYINT NOT NULL,
    period_year       SMALLINT NOT NULL,
    label             NVARCHAR(255) NOT NULL,
    comment           NVARCHAR(1000) NULL,
    created_by        BIGINT NOT NULL,
    created_at        DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET()
);
CREATE INDEX IX_employee_payroll_bonus_profile ON employee_payroll_bonus(profile_user_id);
```

No `updated_at`/edit support: a bonus is added or deleted, never edited in place — kept simple, matching the "record only, no engine wiring yet" scope. `currency` defaults to the employee's own country currency in the UI but is stored explicitly per row, so a bonus paid in a different currency is representable without a special case.

**New endpoints**, under `/api/payroll/employee-configs/{profileUserId}/bonuses`, same permission split as the rest of this screen (`PAYROLL_VIEW_EMPLOYEE_CONFIG` for GET, `PAYROLL_MANAGE_EMPLOYEE_CONFIG` for POST/DELETE):
- `GET /` → `List<EmployeeBonusDto>`, ordered by period descending.
- `POST /` (body: `CreateEmployeeBonusRequest { amount, currency, periodMonth, periodYear, label, comment }`) → the created `EmployeeBonusDto`.
- `DELETE /{bonusId}` → 204.

**Frontend** (both components, same UI block): a small table of existing bonuses (period, amount + currency, label, delete icon) above/below the salary fields, plus an "Ajouter une prime" mini-form (amount, currency select, month/year picker, label, comment) that POSTs immediately on submit (not part of the main "Enregistrer" save — bonuses are their own small CRUD, independent of the salary/config form's save cycle, so adding one doesn't require also re-saving unrelated config fields).

## Non-Goals

- Bonuses do not feed into `PayrollSimulatorService`/`TopologicalEvaluator` — purely tracked records for now. Wiring a one-off bonus into a specific month's charge/IRPP computation is a materially bigger design question (how does a single-month amount interact with the engine's per-parameter-set rubrique pipeline?) deferred until basic tracking is in place and used.
- No bulk employee creation/import from the new list — it's a selection aid, not a new employee-management screen.
- No editing of an existing bonus — delete and re-add instead.

## Testing

- Backend: unit tests for `calculate-net` (delegates to `computeFromGross` correctly, does not persist a `SimulationResult`, permission-gated) and for the bonus CRUD service (create/list/delete, ordering, permission checks).
- Frontend: component tests for the employee-list row-click wiring (delegates to the same selection path as the search picker) and the bonus add/delete flow (list updates optimistically or via refetch after each action) in both `employee-config.component.ts` and `remuneration-section.component.ts`.
