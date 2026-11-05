# Tunisia Payroll Calculation — Configuration Verification (READ-ONLY AUDIT)

**Date:** 2026-11-05
**Scope:** Old-model engine only (`modules.parameterset` + `com.daf360.payroll.engine`:
`PayrollSimulatorService`, `TopologicalEvaluator`, `ConvergenceEngine`,
`IrppCalculatorService`). `payroll_results` (new model) is empty in every environment
checked and out of scope.
**Reference:** `CALCUL_SALAIRE.xlsx` (HR/payroll team's own gross→net worksheet), as
reproduced in the audit prompt. Tolerance: 0.01 TND.
**Method:** Live `DAF360_PAYROLL` SELECTs (localhost + 192.168.1.233) + direct code
reading (file:line below) + a throwaway, non-persisting JUnit test that instantiates
`PayrollSimulatorService`/`TopologicalEvaluator`/`IrppCalculatorService`/
`ConvergenceEngine` directly (no Spring context, no DB writes) and runs the reference
vectors against the real engine code with the real, live-read configuration rows.

---

## 0. Correction to a prior assumption (STEP 1)

`docs/PAYROLL_APP_AUDIT.md` (2026-10-23 update) states Tunisia on 192.168.1.233 "still
only has the 2025 configuration" and that V25 was never run there. **That is no longer
accurate.** Live state as of this audit:

**localhost** — Tunisia (`pays_ref.id = 179`):
| id | fiscal_year | version | status |
|---|---|---|---|
| 9 | 2025 | 1 | **ACTIVE** |
| 10008 | 2026 | 2 | DRAFT |

**192.168.1.233** — Tunisia:
| id | fiscal_year | version | status |
|---|---|---|---|
| 1 | 2025 | 1 | ARCHIVED |
| 20002 | 2026 | 2 | ARCHIVED |
| 20003 | 2027 | 3 | ARCHIVED |
| 20004 | 2028 | 4 | **ACTIVE** |

192.168.1.233 has its **own, independent, further-diverged set of parameter-set rows**
(ids don't correspond to localhost's at all — not just "missing V25," a genuinely
different history), and its currently-active set (`id=20004`, fiscal_year 2028) is
**not a real, usable payroll configuration** — see §1.3. This is new environment drift,
not previously documented, found as a direct side effect of this audit.

Code confirms the engine picks a parameter set purely by `status = 'ACTIVE'`;
`fiscal_year` plays no role (`ParameterSetService`'s active-lookup query filters on
`pays_id` + `status='ACTIVE'` only — confirmed by the fact that `20004`'s fiscal_year
is 2028, three years ahead of "now," and it is still the one used).

### 0.1 Tested configurations

| Label | Where | id | Status | Why tested |
|---|---|---|---|---|
| **Set 9** | localhost | 9 | ACTIVE | This is what every real Tunisia payroll calculation on localhost uses **today**. |
| **Set 10008** | localhost | 10008 | DRAFT | The intended 2026 configuration — not live yet, pending HR→Finance approval. |
| **Set 20004** | 192.168.1.233 | 20004 | ACTIVE | This is what every real Tunisia payroll calculation on 192.168.1.233 uses **today**. Included for completeness even though its content makes a line-by-line worksheet comparison not meaningful (see §1.3) — its own output is reported as a separate finding instead. |

---

## 1. Configuration dump (STEP 2)

### 1.1 Set 9 (localhost, ACTIVE) — `social_charge_rates`, contract type CDI

| charge_code | employee_rate | employer_rate | base_calculation | cap_amount | eval_order |
|---|---|---|---|---|---|
| CNSS | 9.18% | 16.57% | CAPPED_GROSS | 6570 | 10 |
| TFP | 0% | 2.00% | GROSS | — | 20 |
| FOPROLOS | 0% | 1.00% | GROSS | — | 30 |
| AT_MP | 0% | 1.00% | GROSS | — | 40 |

No `CSS` row exists for any contract type in this parameter set. `payroll_rubriques_legacy`
has 5 active rows (INDEMNITE_TRANSPORT, TICKETS_RESTO, PRIME_RENDEMENT, PRIME_ANCIENNETE,
PRIME_EXPERTISE, AVANCE_SALAIRE) — excluded from the vector tests below by design (the
audit prompt asks for the cleanest possible case: CDI, no benefits, no rubriques, no
bonuses). `irpp_brackets`: see §1.4.

### 1.2 Set 10008 (localhost, DRAFT) — `social_charge_rates`, contract type CDI

| charge_code | employee_rate | employer_rate | base_calculation | cap_amount | eval_order |
|---|---|---|---|---|---|
| CNSS | 9.68% | 16.57% | GROSS (uncapped) | — | 0 |
| CSS | 0.50% | 0% | GROSS | — | 5 |
| TFP | 0% | 2.00% | GROSS | — | 20 |
| FOPROLOS | 0% | 1.00% | GROSS | — | 30 |
| AT_MP | 0% | 1.00% | GROSS | — | 40 |

CIVP's own CNSS row is 0%/0% (confirmed full exemption, consistent with the documented
SIVP/CIVP correction — CIVP is the only name that exists, no separate SIVP contract
type in either database).

### 1.3 Set 20004 (192.168.1.233, ACTIVE) — the live configuration on that server

```
social_charge_rates WHERE parameter_set_id = 20004  →  1 row total, any contract type:
  CSS | employee_rate=0% | employer_rate=0% | GROSS | cap=5000
payroll_rubriques_legacy WHERE parameter_set_id = 20004  →  2 rows (TICKETS_RESTO, RAPPEL)
benefits_catalogue WHERE parameter_set_id = 20004  →  1 row (MEAL)
```

**There is no `CNSS`, `TFP`, `FOPROLOS`, or `AT_MP` charge for any contract type in this
parameter set.** Every real payroll calculation for Tunisia run against 192.168.1.233
today computes **zero social charges** (both employee and employer side) for every
contract type — not a misconfigured rate, a structurally empty one. This looks like an
abandoned/placeholder parameter set that was accidentally left `ACTIVE`, not a deliberate
2028 configuration. Flagging this plainly: **this is the single most serious finding of
this audit**, independent of anything the worksheet comparison below covers.

### 1.4 `irpp_brackets` — where they live and what they contain

Stored as a `NVARCHAR(MAX)` JSON column directly on `parameter_sets.irpp_brackets`
(`ParameterSet.java:31-32`) — **not hardcoded**, so a new parameter-set version can
change them freely (also not a separate table).

| Set | Brackets |
|---|---|
| 9 (localhost ACTIVE) | 0–5000 @ 0%, 5000–20000 @ 26%, 20000–30000 @ 28%, 30000–50000 @ 32%, >50000 @ 35% (5 brackets — **does not match** the reference's 8-bracket schedule) |
| 10008 (localhost DRAFT) | 0–5000 @ 0%, 5000–10000 @ 15%, 10000–20000 @ 25%, 20000–30000 @ 30%, 30000–40000 @ 33%, 40000–50000 @ 36%, 50000–70000 @ 38%, >70000 @ 40% — **exact match** to the reference's 8 brackets |
| 20004 (192.168.1.233 ACTIVE) | 0–5000 @ **10.09%**, 5000–10000 @ 15%, 10000–20000 @ 25%, 20000–30000 @ 30%, 30000–40000 @ 33%, 40000–50000 @ 36%, 50000–70000 @ 38%, 70000–90000 @ 39%, >90000 @ 40% (9 brackets, a third distinct schedule, and the first bracket is not 0% here) |

---

## 2. Answers to the code questions (STEP 3)

**Q1 — CNSS employee rate.** Set 9: **9.18%** (not 9.68%). Set 10008: **9.68%** (matches
the worksheet). Set 20004: no CNSS row exists — not applicable. Applies via
`SocialChargeRate.employeeRate` (`SocialChargeRate.java:30-31`), consumed by
`TopologicalEvaluator.applyRate()` (`TopologicalEvaluator.java:391-395`):
`effectiveBase = cap != null ? base.min(cap) : base; return effectiveBase × rate`.
Filtered per contract type at `TopologicalEvaluator.java:121-122`
(`rates.stream().filter(r -> contractType.equals(r.getContractType()))`). CIVP's own
CNSS row is 0%/0% in both localhost sets — confirmed exempt, matching the already-
documented SIVP=CIVP correction.

**Q2 — 10% abattement, cap 2000/year.** **Not applied anywhere.** `taxableBase` is built
in exactly one place, `PayrollSimulatorService.java:165-170`:
```java
BigDecimal taxableBase = gross.subtract(employeeCharges).add(taxableBenefits).add(taxableCredit)...
```
No abattement term exists in this formula, in `IrppCalculatorService`, or anywhere else
in the engine. This is a code-level absence — true for every parameter set, not fixable
by data alone.

**Q3 — Family deductions (married 300, 100/child up to 4).** **Not implemented, and
structurally cannot be** — neither `computeFromGross` nor `computeFromNet`
(`PayrollSimulatorService.java:57-73`) take a `married`/`children` parameter at all.
There is no such column read from `employee_payroll_config` or the request body in this
engine. Confirmed absent at the method-signature level. The 4 family-situation reference
vectors are marked **NOT TESTABLE – input not supported** in §3.

**Q4 — Rounding + annualization.** **Neither exists.** There is no `ROUNDUP` (or any
rounding of the annual base) anywhere in `IrppCalculatorService` or
`PayrollSimulatorService`. More fundamentally: **the taxable base is never annualized at
all.** `PayrollSimulatorService.java:173` calls
`irppCalculator.compute(ps.getIrppBrackets(), taxableBase)` with the **monthly**
`taxableBase` computed at line 166-170, directly against bracket boundaries
(`IrppCalculatorService.java:32-56`) that are unambiguously **annual** values (5000,
10000, 20000... TND — a monthly salary of 70000 TND is not a realistic bracket boundary
for any real employee, but 70000 TND/year is exactly the reference schedule's own
annual boundary). There is no `×12` before the bracket call and no `÷12` after. This is
the single largest contributor to every gross→net mismatch below, confirmed numerically
in §3 and present in **every parameter set tested, including the otherwise-correct
set 10008** — it is a bug in the shared engine code, not something any parameter-set
data change can fix.

**Q5 — IRPP brackets.** See §1.4. Set 10008's bracket **table** (the 8 boundary/rate
pairs) is byte-for-byte correct against the reference. Set 9's and set 20004's are not.
Bracket slicing itself (`IrppCalculatorService.java:42-53`, `if (taxableBase <= lower)
break`, slice = `min(taxableBase, upper) - lower`) is a standard marginal-bracket
implementation and matches the worksheet's `>`/`<` semantics correctly — the bracket
**logic** is not at fault, only (a) which table is stored per set and (b) the missing
annualization from Q4.

**Q6/Q7/Q8 — CSS.** Set 9: **CSS does not exist** in `social_charge_rates` for any
contract type — never computed, never deducted, in the live configuration. Set 10008:
CSS **exists**, rate 0.50% employee / 0% employer — the rate itself matches the
worksheet. But it is modeled as an ordinary `social_charge_rates` row with
`base_calculation = GROSS`, computed by the exact same `applyRate(gross, rate, cap)`
path as CNSS (`TopologicalEvaluator.java:164-176`), which means:
- **Base is raw monthly gross**, not "base2" (the worksheet's annual taxable base after
  abattement and family deductions) — confirmed: at gross=3000, engine CSS = 3000 ×
  0.005 = 15.00 TND; the worksheet's CSS for the same gross is 12.715 TND (computed on
  base2 = 30016, annual, ÷12).
- **CSS reduces the IRPP base** — it is summed into `eval.totalChargesEE()`
  (`TopologicalEvaluator.java:157-180`), which becomes `employeeCharges`
  (`PayrollSimulatorService.java:120`), which is subtracted from `taxableBase`
  (line 166-170) **before** IRPP is computed. Confirmed numerically: at gross=3000,
  `employeeCharges = 305.40` (CNSS 290.40 + CSS 15.00) and `taxableBase = 2694.60 =
  3000 − 305.40`. The worksheet explicitly requires CSS to be computed separately on
  base2 and to **never** reduce the IRPP base.

**Q9 — Working-days base.** `TopologicalEvaluator.java:60`:
`private static final int STANDARD_WORKING_DAYS = 22;` — a hardcoded constant, used only
by `FIXE_JOURNALIER` rubriques (`amount × joursTravailes / 22`,
`TopologicalEvaluator.java:318-323`). Not a parameter-set column, not configurable
without a code change. The worksheet's 26-day base is unrelated to anything in this
engine's charge/IRPP computation (it would only matter for a daily-rate rubrique or
future prorata logic, neither of which is exercised by the vectors in this audit).

**Q10 — Convergence.** Both set 9 and set 10008: `convergence_tolerance = 0.01`,
`max_convergence_iterations = 50` — matches the reference expectation exactly.
`ConvergenceEngine.solve()` (`ConvergenceEngine.java:38-71`) does Newton-Raphson with a
central-difference numeric derivative (`H = 0.01`, lines 26, 100-104), falling back to
bisection (lines 56-64, 73-98) whenever the derivative is below `1e-10` or the current
estimate leaves the `[lo, hi] = [targetNet, 3×targetNet]` bracket, or `f(lo)`/`f(hi)`
share a sign. Confirmed working correctly in practice via the round-trip test in §3.3 —
the solver reliably converges on whatever forward function it's given; the forward
function itself is what's wrong (Q4/Q6-8).

---

## 3. Vector results (STEP 4)

Harness: `ScratchTunisiaVerificationTest` (temporary, deleted after this run — see
final section), instantiating the real `PayrollSimulatorService` with no Spring context,
no DB writes, contract type `CDI`, `joursTravailes = 22`, empty benefits/rubriques lists
in every call.

### 3.1 Gross → Net (tolerance 0.01 TND)

**Set 9 (localhost, ACTIVE — what real calculations use today)**

| Gross | CNSS_EE (ref / actual) | IRPP (ref / actual) | CSS (ref / actual) | NET (ref / actual) | NET diff |
|---|---|---|---|---|---|
| 462 | 44.722 / 42.412 | 0.000 / 0.000 | 1.878 / — (absent) | 415.400 / 419.588 | +4.188 |
| 1000 | 96.800 / 91.800 | 59.438 / 0.000 | 4.065 / — | 839.698 / 908.200 | +68.502 |
| 1500 | 145.200 / 137.700 | 159.000 / 0.000 | 6.097 / — | 1189.703 / 1362.300 | +172.597 |
| 2000 | 193.600 / 183.600 | 264.104 / 0.000 | 8.199 / — | 1534.097 / 1816.400 | +282.303 |
| 3000 | 290.400 / 275.400 | 535.023 / 0.000 | 12.715 / — | 2161.862 / 2724.600 | +562.738 |
| 4000 | 387.200 / 367.200 | 836.453 / 0.000 | 17.231 / — | 2759.116 / 3632.800 | +873.684 |
| 5000 | 484.000 / 459.000 | 1165.247 / 0.000 | 21.747 / — | 3329.007 / 4541.000 | +1211.993 |
| 8000 | 774.400 / 603.126 | 2219.433 / 623.187 | 35.295 / — | 4970.872 / 6773.687 | +1802.815 |
| 10000 | 968.000 / 603.126 | 2941.967 / 1143.187 | 44.327 / — | 6045.707 / 8253.687 | +2207.980 |

**0 / 9 vectors pass.** Max abs diff on NET: **2207.980 TND** (gross=10000).

**Set 10008 (localhost, DRAFT)**

| Gross | CNSS_EE (ref / actual) | IRPP (ref / actual) | CSS (ref / actual) | NET (ref / actual) | NET diff |
|---|---|---|---|---|---|
| 462 | 44.722 / 44.722 | 0.000 / 0.000 | 1.878 / 2.310 | 415.400 / 414.968 | −0.432 |
| 1000 | 96.800 / 96.800 | 59.438 / 0.000 | 4.065 / 5.000 | 839.698 / 898.200 | +58.502 |
| 1500 | 145.200 / 145.200 | 159.000 / 0.000 | 6.097 / 7.500 | 1189.703 / 1347.300 | +157.597 |
| 2000 | 193.600 / 193.600 | 264.104 / 0.000 | 8.199 / 10.000 | 1534.097 / 1796.400 | +262.303 |
| 3000 | 290.400 / 290.400 | 535.023 / 0.000 | 12.715 / 15.000 | 2161.862 / 2694.600 | +532.738 |
| 4000 | 387.200 / 387.200 | 836.453 / 0.000 | 17.231 / 20.000 | 2759.116 / 3592.800 | +833.684 |
| 5000 | 484.000 / 484.000 | 1165.247 / 0.000 | 21.747 / 25.000 | 3329.007 / 4491.000 | +1161.993 |
| 8000 | 774.400 / 774.400 | 2219.433 / 327.840 | 35.295 / 40.000 | 4970.872 / 6857.760 | +1886.888 |
| 10000 | 968.000 / 968.000 | 2941.967 / 597.300 | 44.327 / 50.000 | 6045.707 / 8384.700 | +2338.993 |

**0 / 9 vectors pass.** Max abs diff on NET: **2338.993 TND** (gross=10000). CNSS_EE
itself is an exact match on every vector (confirms the 9.68% rate and uncapped base are
correct) — every TND of mismatch here comes from the missing IRPP annualization and the
CSS structural issue.

**Set 20004 (192.168.1.233, ACTIVE — what real calculations use today on that server)**

Not compared line-by-line against the worksheet (no CNSS exists there at all — see
§1.3); reported for completeness. All 9 gross vectors produce `employeeCharges = 0`
(CSS at 0%/0% contributes nothing) and an IRPP computed directly off raw gross using the
9-bracket schedule in §1.4 (same missing-annualization bug as the other two sets) — e.g.
gross=3000 → IRPP=302.700, NET=2697.300; gross=8000 → IRPP=954.500, NET=7045.500.

### 3.2 Net → Gross (tolerance 0.01 TND)

| Target net | Set 9 actual gross | Set 9 diff | Set 10008 actual gross | Set 10008 diff | Expected gross |
|---|---|---|---|---|---|
| 415.40 | 457.387 | −4.613 | 462.479 | +0.479 | 462.000 |
| 1000 | 1101.081 | −126.325 | 1113.335 | −114.071 | 1227.406 |
| 2000 | 2202.161 | −537.929 | 2226.670 | −513.420 | 2740.090 |
| 3000 | 3303.242 | −1116.788 | 3340.005 | −1080.025 | 4420.030 |
| 3500 | 3853.782 | −1454.074 | 3896.673 | −1411.183 | 5307.856 |
| 5000 | 5505.395 | −2548.790 | 5566.697 | −2487.488 | 8054.185 |

**0 / 6 pass for either set** (set 10008's first row, 415.40 → 462.479, is the closest
at 0.48 TND off — still well outside the 0.01 tolerance). Same root cause as §3.1: the
inverted function is exactly as wrong as the forward one.

### 3.3 Round-trip (`computeFromNet(computeFromGross(g).net) == g`)

This checks the solver's **internal self-consistency**, independent of whether the
forward function matches the worksheet.

| Set | Pass / 9 | Max abs diff |
|---|---|---|
| 9 | 8 / 9 | 0.0101 TND (gross=4000) — one vector just over the 0.01 tolerance |
| 10008 | 9 / 9 | 0.0076 TND (gross=3000) |
| 20004 | 9 / 9 | 0.0048 TND (gross=3000) |

The solver (`ConvergenceEngine`) itself works correctly; it reliably inverts whatever
forward function it is given.

---

## 4. Verdict table (STEP 5.1)

| # | Check | Set 9 (live) | Set 10008 (2026 draft) | Evidence |
|---|---|---|---|---|
| 1 | CNSS employee 9.68% | **MISCONFIGURED** (9.18%, and wrongly capped at 6570) | **CORRECT** | `social_charge_rates` rows, §1.1/1.2; `TopologicalEvaluator.java:164-176` |
| 2 | 10% abattement cap 2000 | **MISCONFIGURED** (absent) | **MISCONFIGURED** (absent) | `PayrollSimulatorService.java:165-170` — no abattement term exists; code-level, same for both |
| 3 | Family deductions | **MISCONFIGURED** (absent — not just unconfigured, not implemented) | **MISCONFIGURED** (absent) | `PayrollSimulatorService.java:57-73` — no married/children parameter |
| 4 | ROUNDUP of annual base | **MISCONFIGURED** (no annualization at all, not just missing rounding) | **MISCONFIGURED** (same) | `PayrollSimulatorService.java:173`, `IrppCalculatorService.java:32-56` |
| 5 | IRPP brackets (8) | **MISCONFIGURED** (different, 5-bracket, outdated schedule) | **CORRECT** (exact match) | §1.4, DB `irpp_brackets` dump |
| 6 | CSS 0.5% — rate | **MISCONFIGURED** (CSS absent entirely) | **CORRECT** (rate value is 0.50%) | §1.1/1.2 |
| 7 | CSS base (base2, not gross) | **CANNOT DETERMINE** (n/a — CSS doesn't exist) | **MISCONFIGURED** (base = gross) | `TopologicalEvaluator.java:164-176`, numeric proof in §2 Q6-8 |
| 8 | CSS not deducted before IRPP | **CANNOT DETERMINE** (n/a) | **MISCONFIGURED** (it is deducted before IRPP) | `PayrollSimulatorService.java:120,166-170` |
| 9 | Employer rates | REPORT ONLY: CNSS 16.57 + TFP 2.00 + FOPROLOS 1.00 + AT_MP 1.00 = **20.57%** total | REPORT ONLY: same 4 components = **20.57%** (CSS employer 0%) | §1.1/1.2. Neither worksheet figure (17.07%/17.57%) is reproduced; AT_MP here is 1.00%, the worksheet's accident-du-travail line is 0.50% |
| 10 | Convergence 0.01 / 50 it. | **CORRECT** | **CORRECT** | DB values confirmed; `ConvergenceEngine.java` behavior confirmed via round-trip test |
| 11 | Gross→Net vectors | **0/9 pass**, max diff 2207.98 | **0/9 pass**, max diff 2338.99 | §3.1 |
| 12 | Net→Gross vectors | **0/6 pass**, max diff 2548.79 | **0/6 pass**, max diff 2487.49 | §3.2 |
| 13 | Round-trip | **8/9 pass**, max diff 0.0101 | **9/9 pass**, max diff 0.0076 | §3.3 |

## 5. Mismatch detail (5.2)

Every gross→net and net→gross vector fails for both sets; full figures are in §3.1/3.2
rather than repeated here. The dominant, single most likely cause for essentially all of
the magnitude of every failure is the same one: **IRPP is computed directly on the
monthly taxable base using annual-scale bracket boundaries, with no ×12/÷12 conversion
anywhere** (Q4). This alone explains differences in the hundreds to low thousands of TND
per month at moderate-to-high salaries (e.g. the 2207.98 TND and 2338.99 TND maximum
diffs above are almost entirely missing IRPP). Secondary, much smaller contributors:
the CNSS rate/cap difference in set 9 (tens of TND) and the CSS structural issue in
set 10008 (a few TND, since the rate itself is small).

## 6. Root cause grouping and estimated monetary impact (5.3)

| Cause | Present in | Impact @ gross=3000 TND/month | Impact @ gross=8000 TND/month |
|---|---|---|---|
| **A — Missing IRPP annualization** (Q4) | Both sets (code-level) | Reference IRPP 535.02 vs engine 0.00 → **−535.02** TND/month under-withheld (net overstated by that much) | Reference IRPP 2219.43 vs set 10008's engine 327.84 → **−1891.59** TND/month under-withheld |
| **B — Wrong CNSS rate + unwarranted cap** (Q1, set 9 only) | Set 9 only | 3000 × (9.68%−9.18%) = **15.00** TND under-withheld (below the 6570 cap, rate-only effect) | At 8000 (above the 6570 cap): correct uncapped would be 774.40, engine computes 603.13 (capped) → **171.27** TND under-withheld (cap now dominates over the rate gap) |
| **C — Outdated IRPP bracket table** (Q5, set 9 only) | Set 9 only | Compounds with Cause A — even a correctly-annualized computation using set 9's own 5-bracket table would not reproduce the reference's tax, since the table itself differs. Not isolated as a separate TND figure to avoid double-counting with Cause A. | Same caveat |
| **D — CSS structurally wrong** (Q6-8, set 10008 only) | Set 10008 only | CSS itself: reference 12.72 vs engine 15.00 → **+2.29** TND over-withheld on the CSS line itself. Indirect effect (CSS wrongly reducing the IRPP base) is folded into Cause A's magnitude and not separately isolated. | CSS itself: reference 35.30 vs engine 40.00 → **+4.70** TND over-withheld |
| **E — No abattement / no family deductions** (Q2, Q3) | Both sets (code-level) | Not isolated as a standalone TND figure — the reference vectors already bake these in, and the current engine's IRPP is so far from the reference baseline (Cause A) that isolating this one factor without first fixing Cause A would require inventing a hypothetical intermediate state. Structurally confirmed absent; real impact scales with family size and is clearly nonzero. | Same caveat |

**Bottom line**: Cause A (missing annualization) dwarfs every other cause by one to two
orders of magnitude at every income level tested. Fixing only the CNSS rate or only CSS
without fixing the IRPP annualization would leave the output still wrong by hundreds to
thousands of TND per month.

## 7. Recommended fixes — NOT APPLIED (5.4)

Ordered by impact; nothing below was implemented.

1. **Annualize the IRPP computation — CODE fix, not data.** In
   `PayrollSimulatorService.java` around line 173, the call needs to become something
   like "annualize `taxableBase` (×12), compute IRPP on the annual figure, divide the
   result by 12" instead of calling `irppCalculator.compute(brackets, taxableBase)`
   directly on the monthly figure. This cannot be fixed by changing parameter-set data —
   the bracket boundaries are correctly annual already (set 10008 proves this); the
   defect is in how the engine applies them. Highest impact of any fix by far.
2. **Add the 10% professional-expense abattement (cap 2000/year) — CODE fix.** Needs a
   new term in the taxable-base computation, and likely a new configurable field (cap
   amount, percentage) on `ParameterSet` or a constant, since neither exists today.
3. **Add family deductions (married/children) — CODE fix**, larger in scope: requires a
   new input to `computeFromGross`/`computeFromNet` (and therefore to every caller —
   `SimulationController`, `EmployeePayrollConfigController`'s "calculate net", cohort
   simulation) plus a source for marital status/children count (likely
   `employee_payroll_config` or the live HR profile via `HrEmployeeClient`, neither of
   which currently carries this for this engine).
4. **Fix CSS — mixed.** Rate (0.50%) is already correct DATA in set 10008. The base
   (should be "base2," not gross) and the before/after-IRPP ordering are CODE issues:
   CSS cannot be modeled as an ordinary `social_charge_rates` row under the current
   engine, because that mechanism always (a) uses gross-based bases and (b) always feeds
   into `employeeCharges`, which always reduces the IRPP base. CSS needs either a
   dedicated computation step after IRPP, or a new charge-type flag that excludes a
   charge from the IRPP-base reduction and lets it reference a different base variable
   (e.g. the post-abattement annual base, once that exists per fix #2).
5. **Update set 9's IRPP bracket table — pure DATA fix**, once a new parameter-set
   version is cut through the normal HR→Finance approval flow (`ParameterSetController`,
   DRAFT→PENDING_FINANCE→ACTIVE). Copying set 10008's `irpp_brackets` JSON is sufficient
   on its own for this one piece.
6. **Fix set 9's CNSS rate (9.18%→9.68%) and remove the 6570 cap** — pure DATA fix, same
   approval flow, copying set 10008's CNSS row.
7. **192.168.1.233's active set (`20004`) needs real charge data** (CNSS/TFP/FOPROLOS/
   AT_MP) before it can be used for anything — this is not a tuning issue, it is
   missing configuration entirely. Whether the right move is to activate a corrected
   version of the existing draft lineage there, or to treat this as a deployment/sync
   problem (should 192.168.1.233 simply be running the same approved version as
   localhost?) is a process question for the team, not something to guess at here.

## 8. Live-environment risk (5.5)

**Every real Tunisia payroll calculation since 2025-01-01 on localhost has gone through
set 9**, which: under-withholds CNSS by 0.50 points of gross (further discounted by an
unintended 6570 cap at higher salaries), uses an outdated 5-bracket IRPP schedule, has
no CSS at all, and — like every other configuration tested — computes IRPP with no
annualization, making every real net-salary and loaded-cost figure this engine has ever
produced for Tunisia substantially overstated on the net side (employees shown as
taking home hundreds to thousands of TND more than the reference model says they
should) from the IRPP step alone. This affects the Simulator, Employee-Config's
"Calculer le net," and any cohort/candidate simulation run for a Tunisian CDI/CDD/CIVP/
STAGE employee.

**Every real Tunisia payroll calculation on 192.168.1.233 since whenever `20004` was
activated** computes essentially **zero** social charges for any contract type, on top
of the same missing-annualization IRPP bug — this is a more severe gap than anything
found on localhost.

Activating set 10008 as-is would fix the CNSS rate/cap and the IRPP bracket table, but
would **not** fix the annualization bug, the abattement, the family deductions, or the
CSS structural issue — all four of those are code defects that no parameter-set version
can work around.

Scope note: these vectors deliberately exclude the parameter sets' own active
`payroll_rubriques_legacy` rows (indemnité transport, tickets resto, primes, avance) to
isolate the tax/charge configuration per the audit's own instructions. A real Simulator
run for an actual employee would include those by default and would differ further from
both the reference sheet and the figures above — that is expected and out of scope here,
not an additional finding.

---

## Open questions (for the payroll firm — not answered here)

1. Employer total: 16.57% CNSS + 0.5% accident de travail = 17.07%, or 17.57%
   ("TOTAL CHARGE")? The worksheet uses both. Separately, the engine's own accident-du-
   travail rate (AT_MP) is configured at 1.00%, not 0.50% — worth confirming which is
   correct for the real rate table.
2. CNRPS rate — not provided in the original questionnaire, but appears in the real
   payroll journal; needs a value if it belongs in this model at all.
3. Is CSS's base really "taxable base after abattement and family deductions" as in the
   worksheet, and is 0.50% the correct 2026 rate?
4. Are the worksheet's family deductions (300 married + 100/child up to 4) the ones the
   firm actually applies, and does the firm also apply the questionnaire's other listed
   deductions (dependent parent, life insurance, CEA, housing-loan interest)?
5. Overtime (75% rate, entered as net by HR) and the 26-day proration base — not modeled
   anywhere in this engine; should they be?
6. (New, from this audit) Should 192.168.1.233 simply run the same approved parameter-
   set version as localhost, or is it meant to diverge? Its currently-active set has no
   usable charge configuration at all.

---

## Appendix — exact reference-vector source

Reproduced from the audit prompt (`CALCUL_SALAIRE.xlsx`, as given — not independently
re-derived from the spreadsheet file itself, which was not provided to this audit).
