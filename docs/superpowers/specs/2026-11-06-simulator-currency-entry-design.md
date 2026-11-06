# Payroll Simulator — Enter Amounts in Any Currency

## Problem

Every country has a platform "working currency" (`payroll_countries.currency_code`) that every simulation is silently computed and shown in — Tunisia's is EUR, not its national currency TND. The individual simulator form (`/payroll/simulator/new`) has no way to say "I'm typing this number in TND" — whatever you type is assumed to already be in the working currency. This produced real confusion this session: a 462 TND figure from a real reference document, typed as-is, was silently treated as 462 EUR, making the two numbers impossible to compare without knowing this assumption existed.

This spec covers making the amount field(s) on that one form currency-aware: pick a currency, type the amount in it, the system converts to the working currency before running the simulation.

**Out of scope** (explicitly, not forgotten):
- The global display-currency switcher (view already-computed results in another currency) — a separate, already-partially-built feature, not addressed here.
- Cohort simulation, employee payroll config, parameter-set administration — only the individual simulator form changes.
- Any change to `PayrollSimulatorService`'s tax/CNSS/IRPP calculations — the engine continues to only ever see an amount already expressed in the working currency; conversion happens strictly before the engine is called.

## Rate source: reuse finance's, don't duplicate

`daf360-facturation-service` already has a complete, admin-maintained FX rate table (`parameter_sets`, rows keyed `RATE_EUR_<CODE>`, e.g. `RATE_EUR_TND = 0.300`), covering 13 currencies including TND. Payroll will **not** build its own parallel rate-maintenance UI. Instead, payroll syncs a read-only copy of these rates on the same schedule it already syncs entity default currencies from finance (`ProfileSyncService`, currently every 15 minutes), and the simulator reads from that local copy at request time — never calling finance synchronously while a user is waiting on a simulation.

This also means a rate finance's admins already maintain (for invoicing/cost purposes) becomes the same rate payroll uses, with nothing to keep in sync by hand.

## Architecture

```
daf360-facturation-service                    daf360-payroll-service                 daf360-payroll-frontend
┌─────────────────────────┐   every 15 min    ┌──────────────────────────┐          ┌─────────────────────────┐
│ parameter_sets           │ <──── poll ─────  │ FxRateSyncService         │          │ SimulatorComponent       │
│  RATE_EUR_TND = 0.300    │                   │ (new, alongside          │          │  + currency <select>     │
│  RATE_EUR_USD = 0.920    │  GET /api/fact/   │  ProfileSyncService)      │          │    next to the amount    │
│  ...                     │  ref/fx-rates     │        │                 │          │    field, both modes     │
└─────────────────────────┘  (new endpoint)    │        v                 │          └───────────┬─────────────┘
                                                │ payroll_fx_rates (new)   │                      │ POST .../individual
                                                │  TND  0.300000           │ <── GET /api/payroll/ref/fx-rates
                                                │  USD  0.920000           │     (new, frontend reads available
                                                │  ...                     │      currencies from this table)
                                                └──────────┬───────────────┘
                                                           │ read at submit time
                                                           v
                                                IndividualSimulationService
                                                 (converts inputNet/inputGross
                                                  to working currency BEFORE
                                                  calling PayrollSimulatorService;
                                                  engine itself is untouched)
```

## Data model changes

**`daf360-facturation-service`** (no schema change — reads existing `parameter_sets`):
- New endpoint `GET /api/fact/ref/fx-rates`, unauthenticated, matching the existing `GET /api/fact/ref/entities-for-sync` convention (same trust model: low-sensitivity reference data, service-to-service sync, no user session involved). Returns `[{ "currencyCode": "TND", "rateToEur": 0.300 }, ...]`, parsed from every `RATE_EUR_*` row.

**`daf360-payroll-service` / `daf360-sql` (`db-payroll`)**:
- New table `payroll_fx_rates`: `currency_code NVARCHAR(3) PRIMARY KEY`, `rate_to_eur DECIMAL(18,6) NOT NULL`, `synced_at DATETIME2 NOT NULL`. A plain cache, fully rebuilt on every sync — no history, no manual edits.
- New columns on `simulation_results`: `input_currency NVARCHAR(3) NULL`, `input_amount_original DECIMAL(18,4) NULL`. Both stay `NULL` when the user never changes the currency away from the country's working one — no redundant data for the common case, and existing rows are unaffected.

## Backend changes

- `FxRateSyncService` (new, small, modeled directly on `ProfileSyncService.syncEntityCurrencies`): polls the new finance endpoint every 15 minutes, upserts `payroll_fx_rates`. Missing/unreachable finance on a given cycle just skips that cycle (existing cached rates remain usable) — same resilience posture as the existing entity-currency sync.
- `GET /api/payroll/ref/fx-rates` (new, payroll-service): returns the cached table's contents plus the requesting country's own working currency, for the frontend to build the dropdown and know what "no conversion" means for this country.
- `SimulationRequest` gains `inputCurrency: String` (nullable; `null`/omitted = working currency, today's behavior, zero change for existing callers like cohort simulation).
- `IndividualSimulationService`: when `inputCurrency` is present and differs from the country's working currency, converts `inputNet`/`inputGross` via `amountInWorking = amountInInputCurrency × rate(inputCurrency→EUR) ÷ rate(working→EUR)` using `payroll_fx_rates`, **before** calling `PayrollSimulatorService` — the engine receives exactly what it does today, a plain amount in the working currency. The original currency and amount are saved onto the result entity unconverted, for display and for a faithful "Dupliquer."
- If `inputCurrency` names a currency not present in `payroll_fx_rates` (sync hasn't run yet, or finance has no rate for it), the request fails with a clear 422 rather than silently falling back to a 1:1 rate — a wrong silent rate is exactly the bug this feature exists to prevent.

## Frontend changes (`daf360-payroll-frontend`, `SimulatorComponent`)

- The amount field's currency suffix (today a static label reading the country's `devise`) becomes a real `daf-select`, populated from `GET /api/payroll/ref/fx-rates`, for both the net field (NET_TO_BRUT) and the gross field (BRUT_TO_NET) — defaulting to the country's working currency.
- Changing the currency never rescales what's already typed — the number is reinterpreted, not recalculated, until submit.
- `submit()` sends `inputCurrency` whenever it differs from the working currency.
- `prefill()` (used by both "Modifier la simulation" and "Dupliquer") restores `inputCurrency` + the original amount from the result when present, so reopening a simulation shows exactly what was typed, not a converted number in a currency the user never chose.
- Result view: when `inputCurrency` differs from the working currency, add one line under "Entrées du calcul" showing the original figure (e.g. "Saisi comme : 1 165,00 TND"). When it's null (the common case, unchanged), nothing changes on screen.

## Testing

- Backend: unit tests on the new conversion math in `IndividualSimulationService` (round far enough from a round number to catch a transposed multiply/divide), plus the 422-on-unknown-currency path. The existing 4 Tunisia regression tests in `PayrollSimulatorServiceTunisiaConfigTest` are untouched (they call `PayrollSimulatorService` directly, which this feature never modifies).
- Manual: the TND reference-document scenario that motivated this — type 198 TND as the gross, confirm it converts to the same EUR-equivalent gross this session already validated, with the same resulting net/IRPP/charges as the equivalent EUR-denominated run.
