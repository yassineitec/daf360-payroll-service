# New-Model Payroll Engine Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `PayrollOrchestrator` (the engine behind "Calcul de paie"/"Résultats de paie") compute IRPP/CSS/INCOME_TAX, "Net à payer", and "Coût employeur" correctly for every country, and bring Tunisia's rates in line with the already-validated old-model values.

**Architecture:** A new `payer` column on `payroll_rubriques` distinguishes employee- vs employer-side contributions; a new strate-4 `FORMULE` rubrique per country finally produces the `STRATE_4` value that IRPP/CSS/INCOME_TAX have always (harmlessly) referenced; `ResultPersister` is rewritten to derive net/employer-cost from the rubriques actually computed, instead of the old-model charge bridge and a miswired `STRATE_5`-as-net.

**Tech Stack:** Spring Boot 4.0.5, Java 17, Spring Data JPA, Spring SpEL (`FormuleCalculator`), SQL Server (`daf360-sql` migrations), JUnit 5 + AssertJ + Mockito (`spring-boot-starter-test`).

---

## Facts confirmed by reading the real code (2026-11-06) — read this before starting any task

These correct or sharpen the spec's deliberately-open questions. Trust these over the spec's provisional wording.

1. **`assiette_code = 'STRATE_4'` on `IRPP`/`CSS`/`INCOME_TAX` never needs to change.** `SequenceValidator.SYSTEM_VARS` always includes `STRATE_4` (it's a strate-total alias, not a rubrique code), so these rows have never been "dangling references" structurally — they've simply always resolved to zero because nothing was tagged `strate=4`. Task 2 below only *adds* a new rubrique; it does not touch the existing `IRPP`/`CSS`/`INCOME_TAX` rows at all.
2. **`FormuleCalculator` (mode `FORMULE`) only substitutes named *context variables* (`ExecutionContext.getVariables()`), never `param_key_*`/the `parameters` JSON map.** Those `param_key_*` fields are a different mechanism used only by `TAUX_PCT`/`MONTANT_FIXE`/`BAREME_PROGRESSIF`/`ANNUALISE_BAREME`. This means the new strate-4 formula cannot reference `abattement_rate_tn` etc. via a `param_key_*` field — it references them as plain tokens in `formula_expression`, which only resolve if something first copies them into `ExecutionContext`'s variable map. Task 4 adds exactly that: a generic copy of every scalar (non-list) `parameters` entry into context variables, so any country's `FORMULE` rubrique can reference any of its own parameters by name, with no per-key wiring.
3. **`AT_PATRONAL`'s new-model rate is already 1.0 (1%) — correct, not stale.** Only `taux_cnss_sal_tn` (9.18 → 9.68) and `taux_css_tn` (1.0 → 0.5) need correcting, confirmed by reading Tunisia's live `payroll_parameter_sets.parameters` JSON directly.
4. **No new `aggregate_*` columns are needed.** `PayrollResult.strate5` already persists exactly "the sum of strate-5 rubriques" — once bug 1 is fixed, that *is* the tax total. `ResultPersister` computes employee/employer charges as local variables from `items`, not new persisted fields.
5. **Employee/employer charge filtering uses `nature == "COTISATION" && payer == ...`, not strate number.** This exactly matches the spec's stated formula and avoids any risk of the new strate-4 `TAXE`-nature rubrique being double-counted (it's never summed by nature="COTISATION", and the tax total comes from the `STRATE_5` context variable, which only includes strate-5 items — the new rubrique is strate 4).
6. **No new `nature` value needed.** The `nature` CHECK constraint (`CK_rubrique_nature`) only allows `AVANTAGE`/`TAXE`/`COTISATION`/`RETENUE`/`GAIN`. The new strate-4 rubriques use `nature='TAXE'` (closest fit) — this is safe because nothing in the new `ResultPersister` logic sums "all TAXE items by nature"; taxes come from `STRATE_5` (strate-based), charges from `COTISATION`+`payer` (both conditions, so a `TAXE`-nature row is never swept in regardless of its strate).
7. **This is the first production use of `mode_calcul='FORMULE'` in this engine** — no existing rubrique uses it, and there's no existing `FormuleCalculator` test. Treat Task 7 (the acceptance test) as your real confidence check for this mechanism, not just a regression guard.
8. **Known pre-existing gap, out of scope, worth one comment in the admin code**: `EngineConfigService.missingKeys()` only scans `param_key_taux`/`param_key_plafond`/`param_key_bareme` across rubriques to warn admins about missing JSON keys before activating a param set. It has no visibility into parameter names embedded directly inside a `FORMULE` rubrique's `formula_expression` text (like `abattement_rate_tn`), because that mechanism didn't exist before this change. Not fixed here — flag it with a one-line comment where you add the `payer` field, so a future reader isn't surprised that activating a param set missing `abattement_rate_tn` fails silently (the formula evaluates to a wrong number, not an error) rather than being caught by `missingKeys()`.

---

### Task 1: `daf360-sql` migration — `payer` column, strate-4 rubriques, Tunisia rate corrections

**Files:**
- Create: `daf360-sql/db-payroll/migration/V35__new_model_strate4_payer_tunisia_rates.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V35__new_model_strate4_payer_tunisia_rates.sql
-- Fixes the new-model payroll engine (PayrollOrchestrator) for every country:
--   1) payroll_rubriques.payer distinguishes employee- vs employer-side COTISATION rows
--      (strate 3 currently mixes both sides under one bucket -- see ResultPersister).
--   2) A new strate-4 "assiette" rubrique per country actually produces STRATE_4, which
--      IRPP/CSS/INCOME_TAX already read via assiette_code='STRATE_4' (always a valid
--      SequenceValidator system variable) but which nothing populated until now --
--      explaining why those rubriques always computed to 0.00.
--   3) Tunisia-only rate corrections to match the already-validated old-model config
--      (docs/PAYROLL_CONFIG_VERIFICATION.md): CNSS employee 9.18% -> 9.68%, CSS 1% flat
--      -> 0.5% on the post-abattement base, 5-tier IRPP bracket table -> the validated
--      8-tier table. Egypt's rates are untouched -- no validated reference exists yet,
--      and its new strate-4 rubrique deliberately has no abattement/family-deduction step.
SET NOCOUNT ON;

-- 1) payer column.
ALTER TABLE dbo.payroll_rubriques ADD payer NVARCHAR(10) NULL;
GO
ALTER TABLE dbo.payroll_rubriques
    ADD CONSTRAINT CK_rubrique_payer CHECK (payer IN ('EMPLOYEE','EMPLOYER') OR payer IS NULL);
GO

UPDATE dbo.payroll_rubriques
   SET payer = 'EMPLOYEE'
 WHERE code IN ('CNSS_SALARIE', 'SI_SALARIE');

UPDATE dbo.payroll_rubriques
   SET payer = 'EMPLOYER'
 WHERE code IN ('CNSS_PATRONAL', 'TFP', 'FOPROLOS', 'AT_PATRONAL', 'SI_PATRONAL', 'SHIF_PATRONAL');

-- 2) Strate-4 "assiette" rubriques, one per country, between strate 3 and strate 5 in
--    display order. assiette_code is NULL: FormuleCalculator ignores the
--    dispatcher-resolved assiette entirely, it only reads named context variables
--    (see ContextLoader's generic parameter-copy and PayrollOrchestrator's
--    IS_MARRIED/CHILDREN_COUNT seeding, both added later in this plan).
--
--    Tunisia's formula replicates the old model's validated pipeline: annualize the
--    monthly taxable base (BRUT + AVANTAGES - CNSS_SALARIE, x12), subtract abattement
--    (min(annual_base x abattement_rate_tn, abattement_cap_tn)) and family deduction
--    (IS_MARRIED x family_married_tn + min(CHILDREN_COUNT, family_max_children_tn) x
--    family_per_child_tn), floor at zero, round up, divide by 12 -- producing a
--    monthly-equivalent assiette so ANNUALISE_BAREME's own x12 step reconstructs the
--    correct annual adjusted base before applying brackets.
--
--    Egypt's formula has no deduction step at all (unvalidated, flagged in its own
--    label) -- so annualizing then dividing by 12 is a no-op; it is written out in
--    full anyway for symmetry and so a future validated version is a small diff.
INSERT INTO dbo.payroll_rubriques
    (country_id, code, label_fr, label_en, strate, nature, mode_calcul, assiette_code,
     formula_expression, periodicite, prorata_applicable, display_order, active)
VALUES
    (7, 'ASSIETTE_IRPP', 'Base imposable IRPP', 'IRPP taxable base', 4, 'TAXE', 'FORMULE', NULL,
     'T(Math).ceil(T(Math).max(((BRUT + AVANTAGES - CNSS_SALARIE) * 12) - T(Math).min(((BRUT + AVANTAGES - CNSS_SALARIE) * 12) * abattement_rate_tn, abattement_cap_tn) - ((IS_MARRIED * family_married_tn) + (T(Math).min(CHILDREN_COUNT, family_max_children_tn) * family_per_child_tn)), 0)) / 12',
     'MENSUEL', 0, 45, 1),
    (8, 'ASSIETTE_INCOME_TAX', 'Base imposable (NON VALIDEE)', 'Taxable base (UNVALIDATED)', 4, 'TAXE', 'FORMULE', NULL,
     'T(Math).max((BRUT + AVANTAGES - SI_SALARIE) * 12, 0) / 12',
     'MENSUEL', 0, 35, 1);
GO

-- 3) Tunisia-only: CNSS employee rate + CSS rate/base + 8-tier IRPP brackets, plus the
--    new abattement/family-deduction parameters ASSIETTE_IRPP's formula reads by name.
--    Targets whichever Tunisia param set is currently ACTIVE, not a hardcoded id --
--    same defensive pattern as
--    one-off/payroll/2026-11-06_fix_tunisia_accident_travail_rate_on_active_set.sql,
--    since this param set has already been re-versioned multiple times.
UPDATE dbo.payroll_parameter_sets
   SET parameters = N'{"salaire_base_tn":2500.0,"taux_cnss_sal_tn":9.68,"taux_cnss_empl_tn":16.57,"plafond_cnss_mens_tn":6570.0,"taux_tfp_tn":2.0,"taux_foprolos_tn":1.0,"taux_at_tn":1.0,"taux_css_tn":0.5,"seuil_css_annuel_tn":6000.0,"abattement_rate_tn":0.10,"abattement_cap_tn":2000,"family_married_tn":300,"family_per_child_tn":100,"family_max_children_tn":4,"bareme_irpp_tn":[{"min":0,"max":5000,"rate":0},{"min":5000,"max":10000,"rate":15},{"min":10000,"max":20000,"rate":25},{"min":20000,"max":30000,"rate":30},{"min":30000,"max":40000,"rate":33},{"min":40000,"max":50000,"rate":36},{"min":50000,"max":70000,"rate":38},{"min":70000,"max":null,"rate":40}]}'
 WHERE country_id = 7 AND status = 'ACTIVE';
GO
```

- [ ] **Step 2: Apply it locally (and only this file — do not run any other pending migration)**

```bash
cd daf360-sql
powershell -File apply.ps1 -Apply -Db payroll -Only V35__new_model_strate4_payer_tunisia_rates.sql -Yes
```
Expected: `V35__new_model_strate4_payer_tunisia_rates.sql` reported `APPLIED`. (A known dangerous backlog — `V25__salary_advances.sql`, `S01__seed_tunisia_egypt.sql`, `S02__minimal_parameter_set_for_pays.sql`, `fix_V25_policy_countries.sql` — must stay untouched; `-Only` ensures that.)

- [ ] **Step 3: Verify directly against the DB**

```bash
docker exec -e "SQLCMDPASSWORD=$DB_PASSWORD" timesheet-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost,1433 -U sa -C -I -b -d DAF360_PAYROLL -Q "SET NOCOUNT ON; SELECT country_id, code, strate, payer FROM dbo.payroll_rubriques WHERE payer IS NOT NULL OR code LIKE 'ASSIETTE%' ORDER BY country_id, display_order;"
```
Expected: 6 `payer`-tagged rows (3 `EMPLOYEE`, wait — 2 `EMPLOYEE` [`CNSS_SALARIE`, `SI_SALARIE`] + 6 `EMPLOYER` [`CNSS_PATRONAL`,`TFP`,`FOPROLOS`,`AT_PATRONAL`,`SI_PATRONAL`,`SHIF_PATRONAL`]) plus the 2 new `ASSIETTE_*` rows (`payer` NULL, `strate`=4).

- [ ] **Step 4: Commit**

```bash
cd daf360-sql
git add db-payroll/migration/V35__new_model_strate4_payer_tunisia_rates.sql
git commit -m "feat(payroll): add payer column, strate-4 assiette rubriques, Tunisia rate fix

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: `PayrollRubriqueDef` entity — add `payer`

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/payroll/entity/PayrollRubriqueDef.java`

- [ ] **Step 1: Add the field**

Current end of the class (after `active`):
```java
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
```

Replace with:
```java
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * EMPLOYEE | EMPLOYER | null — which side a COTISATION rubrique is charged to.
     * Null for GAIN/TAXE natures (a tax is inherently withheld from the employee without
     * needing this flag; an earning isn't a "charge" either side pays).
     */
    @Column(name = "payer", length = 10)
    private String payer;
}
```

- [ ] **Step 2: Compile**

```bash
cd daf360-payroll-service
mvn -q -o compile -DskipTests
```
Expected: no output (success).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/payroll/entity/PayrollRubriqueDef.java
git commit -m "feat(payroll): add payer field to PayrollRubriqueDef

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: `RubriqueResultItem` DTO + wire it from `PayrollOrchestrator`

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/payroll/dto/RubriqueResultItem.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/payroll/orchestrator/PayrollOrchestrator.java`

- [ ] **Step 1: Add `payer` to the DTO**

Current:
```java
    /** Nature of the rubrique: GAIN | RETENUE | COTISATION | TAXE | AVANTAGE */
    private String nature;

    /** Strate (calculation tier) to which this rubrique belongs (1–5). */
    private int strate;
```

Replace with:
```java
    /** Nature of the rubrique: GAIN | RETENUE | COTISATION | TAXE | AVANTAGE */
    private String nature;

    /** EMPLOYEE | EMPLOYER | null — see {@link com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef#getPayer()}. */
    private String payer;

    /** Strate (calculation tier) to which this rubrique belongs (1–5). */
    private int strate;
```

- [ ] **Step 2: Populate it in `PayrollOrchestrator.run()`**

Current (inside the `for (PayrollRubriqueDef rubrique : rubriques)` loop):
```java
            RubriqueResultItem item = RubriqueResultItem.builder()
                .rubriqueCode(rubrique.getCode())
                .labelFr(rubrique.getLabelFr())
                .labelEn(rubrique.getLabelEn())
                .nature(rubrique.getNature())
                .strate(rubrique.getStrate())
```

Replace with:
```java
            RubriqueResultItem item = RubriqueResultItem.builder()
                .rubriqueCode(rubrique.getCode())
                .labelFr(rubrique.getLabelFr())
                .labelEn(rubrique.getLabelEn())
                .nature(rubrique.getNature())
                .payer(rubrique.getPayer())
                .strate(rubrique.getStrate())
```

- [ ] **Step 3: Compile**

```bash
mvn -q -o compile -DskipTests
```
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/payroll/dto/RubriqueResultItem.java src/main/java/com/daf360/payroll/modules/payroll/orchestrator/PayrollOrchestrator.java
git commit -m "feat(payroll): carry payer through to RubriqueResultItem

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: `ContextLoader` — generically expose scalar parameters as formula variables

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/payroll/orchestrator/ContextLoader.java`
- Test: `daf360-payroll-service/src/test/java/com/daf360/payroll/modules/payroll/orchestrator/ContextLoaderParameterVariablesTest.java`

This is new behavior worth a focused unit test before wiring it into the full `load()` method — `FormuleCalculator` depends entirely on this to let a `FORMULE` rubrique reference `abattement_rate_tn` etc. by name (see Fact 2 above).

- [ ] **Step 1: Write the failing test**

```java
package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextLoaderParameterVariablesTest {

    @Test
    void copiesScalarParametersIntoVariablesByTheirOwnKeyName() {
        Map<String, Object> params = Map.of(
                "abattement_rate_tn", 0.10,
                "abattement_cap_tn", 2000,
                "salaire_base_tn", 2500.0
        );

        ExecutionContext ctx = new ExecutionContext(
                1L, 7L, 179L, 2026, 1, "CDI", BigDecimal.valueOf(22), params);
        ContextLoader.copyScalarParametersAsVariables(ctx, params);

        assertThat(ctx.getVariable("abattement_rate_tn")).isEqualByComparingTo("0.10");
        assertThat(ctx.getVariable("abattement_cap_tn")).isEqualByComparingTo("2000");
        assertThat(ctx.getVariable("salaire_base_tn")).isEqualByComparingTo("2500.0");
    }

    @Test
    void skipsNonScalarParameters() {
        Map<String, Object> params = Map.of(
                "bareme_irpp_tn", List.of(Map.of("min", 0, "max", 5000, "rate", 0))
        );

        ExecutionContext ctx = new ExecutionContext(
                1L, 7L, 179L, 2026, 1, "CDI", BigDecimal.valueOf(22), params);
        ContextLoader.copyScalarParametersAsVariables(ctx, params);

        assertThat(ctx.hasVariable("bareme_irpp_tn")).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -o test -Dtest=ContextLoaderParameterVariablesTest
```
Expected: compile error — `ContextLoader.copyScalarParametersAsVariables` does not exist yet.

- [ ] **Step 3: Add the method and call it from `load()`**

Current `ContextLoader.load()`, the parameter-parsing block through the system-variable seeding:
```java
        // 3. Parse parameters JSON to Map<String, Object>
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(
                paramSet.getParameters(),
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Paramètres du jeu de paramètres : JSON illisible", e);
        }

        // 4. Build context
        BigDecimal joursOuvres = request.getJoursOuvresMois() != null
            ? request.getJoursOuvresMois()
            : BigDecimal.valueOf(22);
        String contractType = request.getContractTypeCode() != null
            ? request.getContractTypeCode()
            : "CDI";

        ExecutionContext ctx = new ExecutionContext(
            request.getEmployeeId(), country.getId(), request.getPaysId(),
            request.getPeriodYear(), request.getPeriodMonth(),
            contractType, joursOuvres, params
        );

        // 5. Pre-load system variables
        ctx.putVariable("JOURS_OUVRES", joursOuvres);
        ctx.putVariable("MOIS", BigDecimal.valueOf(request.getPeriodMonth()));
        ctx.putVariable("ANNEE", BigDecimal.valueOf(request.getPeriodYear()));

        return ctx;
    }
```

Replace with:
```java
        // 3. Parse parameters JSON to Map<String, Object>
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(
                paramSet.getParameters(),
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Paramètres du jeu de paramètres : JSON illisible", e);
        }

        // 4. Build context
        BigDecimal joursOuvres = request.getJoursOuvresMois() != null
            ? request.getJoursOuvresMois()
            : BigDecimal.valueOf(22);
        String contractType = request.getContractTypeCode() != null
            ? request.getContractTypeCode()
            : "CDI";

        ExecutionContext ctx = new ExecutionContext(
            request.getEmployeeId(), country.getId(), request.getPaysId(),
            request.getPeriodYear(), request.getPeriodMonth(),
            contractType, joursOuvres, params
        );

        // 5. Pre-load system variables
        ctx.putVariable("JOURS_OUVRES", joursOuvres);
        ctx.putVariable("MOIS", BigDecimal.valueOf(request.getPeriodMonth()));
        ctx.putVariable("ANNEE", BigDecimal.valueOf(request.getPeriodYear()));

        // 6. Expose every scalar parameter (taux_*, abattement_*, family_* etc.) as a
        //    context variable too, by its own key name — FORMULE-mode rubriques only
        //    read named context variables, never the parameters map directly (unlike
        //    TAUX_PCT/MONTANT_FIXE/ANNUALISE_BAREME, which use param_key_* fields).
        //    List-valued parameters (bareme_*) are skipped: they can't become a single
        //    BigDecimal and aren't needed as one — ANNUALISE_BAREME reads them via
        //    param_key_bareme directly from the parameters map.
        copyScalarParametersAsVariables(ctx, params);

        return ctx;
    }

    /**
     * Note: EngineConfigService.missingKeys() only scans param_key_taux/plafond/bareme
     * across rubriques to warn admins about missing JSON keys before activating a param
     * set. It has no visibility into parameter names embedded directly inside a FORMULE
     * rubrique's formula_expression (like abattement_rate_tn) — activating a param set
     * missing one evaluates to a wrong number silently, not a caught validation error.
     */
    static void copyScalarParametersAsVariables(ExecutionContext ctx, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            BigDecimal value = toScalar(entry.getValue());
            if (value != null) {
                ctx.putVariable(entry.getKey(), value);
            }
        }
    }

    private static BigDecimal toScalar(Object raw) {
        if (raw instanceof BigDecimal v) return v;
        if (raw instanceof Double v) return BigDecimal.valueOf(v);
        if (raw instanceof Integer v) return BigDecimal.valueOf(v.longValue());
        if (raw instanceof Long v) return BigDecimal.valueOf(v);
        if (raw instanceof String v) {
            try {
                return new BigDecimal(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
```

- [ ] **Step 4: Run the test again to verify it passes**

```bash
mvn -q -o test -Dtest=ContextLoaderParameterVariablesTest
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/payroll/orchestrator/ContextLoader.java src/test/java/com/daf360/payroll/modules/payroll/orchestrator/ContextLoaderParameterVariablesTest.java
git commit -m "feat(payroll): expose scalar parameters as FORMULE-readable context variables

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: `PayrollOrchestrator` — seed `IS_MARRIED`/`CHILDREN_COUNT`

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/payroll/orchestrator/PayrollOrchestrator.java`

Extends the existing `applyEmployeeBaseSalary` lookup (it already fetches the employee's `EmployeePayrollConfig` by `profileUserId` for the base-salary override) — reuses the same DB call instead of a second one, and seeds the two new variables regardless of whether a configured gross salary exists.

- [ ] **Step 1: Read the current method fully (shown here, from the file already read in this plan's investigation)**

```java
    private void applyEmployeeBaseSalary(ExecutionContext ctx, List<PayrollRubriqueDef> rubriques, Long employeeId) {
        if (employeeId == null) return;
        BigDecimal gross = employeeConfigRepo.findByProfileUserId(employeeId)
            .map(c -> c.getCurrentGrossSalary())
            .filter(g -> g != null && g.signum() > 0)
            .orElse(null);
        if (gross == null) {
            log.info("applyEmployeeBaseSalary: pas de salaire configuré pour l'employé {} — montant du jeu de paramètres", employeeId);
            return;
        }
        baseSalaryRubrique(rubriques)
            .ifPresent(r -> {
                ctx.getParameters().put(r.getParamKeyTaux(), gross);
                log.debug("applyEmployeeBaseSalary: employé {} — {} = {}", employeeId, r.getParamKeyTaux(), gross);
            });
    }
```

- [ ] **Step 2: Replace it**

```java
    private void applyEmployeeBaseSalary(ExecutionContext ctx, List<PayrollRubriqueDef> rubriques, Long employeeId) {
        if (employeeId == null) return;
        var config = employeeConfigRepo.findByProfileUserId(employeeId).orElse(null);

        // IS_MARRIED/CHILDREN_COUNT feed Tunisia's ASSIETTE_IRPP family-deduction formula
        // (strate 4). Seeded as 0/false when the employee has no config row at all, same
        // "fall back to no adjustment" posture as the base-salary lookup below.
        boolean isMarried = config != null && Boolean.TRUE.equals(config.getIsMarried());
        int childrenCount = config != null && config.getChildrenCount() != null ? config.getChildrenCount() : 0;
        ctx.putVariable("IS_MARRIED", isMarried ? BigDecimal.ONE : BigDecimal.ZERO);
        ctx.putVariable("CHILDREN_COUNT", BigDecimal.valueOf(childrenCount));

        BigDecimal gross = config != null ? config.getCurrentGrossSalary() : null;
        if (gross == null || gross.signum() <= 0) {
            log.info("applyEmployeeBaseSalary: pas de salaire configuré pour l'employé {} — montant du jeu de paramètres", employeeId);
            return;
        }
        baseSalaryRubrique(rubriques)
            .ifPresent(r -> {
                ctx.getParameters().put(r.getParamKeyTaux(), gross);
                log.debug("applyEmployeeBaseSalary: employé {} — {} = {}", employeeId, r.getParamKeyTaux(), gross);
            });
    }
```

- [ ] **Step 3: Compile**

```bash
mvn -q -o compile -DskipTests
```
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/payroll/orchestrator/PayrollOrchestrator.java
git commit -m "feat(payroll): seed IS_MARRIED/CHILDREN_COUNT for the strate-4 formula

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: `ResultPersister` — derive net/employer-cost from `payer`, stop using the charge bridge

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/payroll/orchestrator/ResultPersister.java`
- Test: `daf360-payroll-service/src/test/java/com/daf360/payroll/modules/payroll/orchestrator/ResultPersisterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResultPersisterTest {

    @Mock
    private PayrollResultRepository resultRepo;

    @Test
    void netAndEmployerCostComeFromRubriquesNotTheChargeBridge() {
        ResultPersister persister = new ResultPersister(resultRepo, new ObjectMapper());
        when(resultRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExecutionContext ctx = new ExecutionContext(1L, 7L, 179L, 2026, 1, "CDI",
                BigDecimal.valueOf(22), java.util.Map.of());
        ctx.putVariable("STRATE_1", new BigDecimal("2500.00"));
        ctx.putVariable("STRATE_2", BigDecimal.ZERO);
        ctx.putVariable("STRATE_3", new BigDecimal("619.25")); // mixed EE+ER, unused by the fix
        ctx.putVariable("STRATE_4", new BigDecimal("2659.00"));
        ctx.putVariable("STRATE_5", new BigDecimal("100.00")); // IRPP + CSS
        // Simulates the old-model charge bridge still running (per spec: untouched, just unread).
        ctx.putVariable("CHARGES_ER", new BigDecimal("9999.99"));

        List<RubriqueResultItem> items = List.of(
                item("CNSS_SALARIE", "COTISATION", "EMPLOYEE", 3, "241.90"),
                item("CNSS_PATRONAL", "COTISATION", "EMPLOYER", 3, "414.25"),
                item("TFP", "COTISATION", "EMPLOYER", 3, "50.00"),
                item("FOPROLOS", "COTISATION", "EMPLOYER", 3, "25.00"),
                item("AT_PATRONAL", "COTISATION", "EMPLOYER", 3, "25.00"),
                item("ASSIETTE_IRPP", "TAXE", null, 4, "2659.00"),
                item("IRPP", "TAXE", null, 5, "80.00"),
                item("CSS", "TAXE", null, 5, "20.00")
        );

        PayrollResult result = persister.persist(ctx, items, 7L, "test", true, null);

        // gross (2500) - employee charges (241.90) - taxes (STRATE_5 = 100.00)
        assertThat(result.getAggregateNet()).isEqualByComparingTo("2158.10");
        // employer charges = CNSS_PATRONAL + TFP + FOPROLOS + AT_PATRONAL, NOT CHARGES_ER
        assertThat(result.getAggregateEmployerCharges()).isEqualByComparingTo("514.25");
        assertThat(result.getLoadedCost()).isEqualByComparingTo("3014.25"); // 2500 + 514.25
    }

    private static RubriqueResultItem item(String code, String nature, String payer, int strate, String amount) {
        return RubriqueResultItem.builder()
                .rubriqueCode(code)
                .nature(nature)
                .payer(payer)
                .strate(strate)
                .amount(new BigDecimal(amount))
                .build();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -o test -Dtest=ResultPersisterTest
```
Expected: `aggregateNet`/`aggregateEmployerCharges` assertions fail (currently `100.00`-derived-from-STRATE_5 passes through as net with no gross/charges subtraction, and employer charges come back `9999.99` from `CHARGES_ER`).

- [ ] **Step 3: Rewrite `persist()`**

Current:
```java
        BigDecimal strate1 = nvl(ctx.getVariable("STRATE_1"));
        BigDecimal strate2 = nvl(ctx.getVariable("STRATE_2"));
        BigDecimal gross = strate1.add(strate2);
        // Charges PATRONALES : CHARGES_ER, injecté par l'orchestrateur depuis les taux du jeu de
        // paramètres actif (TopologicalEvaluator, voir PayrollOrchestrator#injectChargeVariables).
        // Auparavant STRATE_3 était enregistré ici — c'est la CNSS SALARIALE (retenue sur le
        // salaire), pas une charge de l'employeur : le coût employeur s'en trouvait faux.
        // Sans taux employeur chargés (aucun jeu actif), la charge vaut 0 : le coût = le brut.
        BigDecimal employerCharges = ctx.hasVariable("CHARGES_ER")
            ? nvl(ctx.getVariable("CHARGES_ER")).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        result.setAggregateGross(gross);
        result.setAggregateNet(nvl(ctx.getVariable("STRATE_5")));
        result.setAggregateEmployerCharges(employerCharges);
        result.setAggregateIrpp(ctx.getVariable("IRPP"));
        result.setLoadedCost(gross.add(employerCharges));
```

Replace with:
```java
        BigDecimal strate1 = nvl(ctx.getVariable("STRATE_1"));
        BigDecimal strate2 = nvl(ctx.getVariable("STRATE_2"));
        BigDecimal gross = strate1.add(strate2);

        // Employee/employer COTISATION totals come from the rubriques actually computed
        // this run, filtered by `payer` — NOT from CHARGES_ER (the old-model charge
        // bridge's injected variable). The bridge still runs (PayrollOrchestrator#
        // injectChargeVariables, best-effort) but nothing here reads its output anymore:
        // every country's own CNSS/TFP/FOPROLOS/AT/SI/SHIF rubriques are now summed
        // directly, so a country-specific charge (TFP, FOPROLOS — no old-model
        // equivalent at all) is no longer silently dropped from the total.
        BigDecimal employeeCharges = sumByNatureAndPayer(items, "COTISATION", "EMPLOYEE");
        BigDecimal employerCharges = sumByNatureAndPayer(items, "COTISATION", "EMPLOYER");

        // Taxes (IRPP/CSS/INCOME_TAX) are strate 5 by construction — STRATE_4 is the new
        // intermediate assiette they read, never itself a payable amount.
        BigDecimal taxes = nvl(ctx.getVariable("STRATE_5"));

        result.setAggregateGross(gross);
        result.setAggregateNet(gross.subtract(employeeCharges).subtract(taxes));
        result.setAggregateEmployerCharges(employerCharges);
        result.setAggregateIrpp(ctx.getVariable("IRPP"));
        result.setLoadedCost(gross.add(employerCharges));
```

Then add the helper method (anywhere after `persist()`, before `nvl()`):
```java
    private BigDecimal sumByNatureAndPayer(List<RubriqueResultItem> items, String nature, String payer) {
        return items.stream()
            .filter(i -> nature.equals(i.getNature()) && payer.equals(i.getPayer()))
            .map(RubriqueResultItem::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    }
```

Also remove the now-unused `RoundingMode` import if it becomes unused — it is still used by `sumByNatureAndPayer`'s `setScale`, so the import stays as-is.

- [ ] **Step 4: Run the test again to verify it passes**

```bash
mvn -q -o test -Dtest=ResultPersisterTest
```
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/payroll/orchestrator/ResultPersister.java src/test/java/com/daf360/payroll/modules/payroll/orchestrator/ResultPersisterTest.java
git commit -m "fix(payroll): derive net/employer-cost from payer-tagged rubriques

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: Acceptance test — Tunisia net matches the old model, loaded cost correctly exceeds it

**Files:**
- Test: `daf360-payroll-service/src/test/java/com/daf360/payroll/modules/payroll/orchestrator/PayrollOrchestratorTunisiaEngineFixTest.java`

This exercises the real `PayrollOrchestrator.run()` end to end — real `ContextLoader`, `SequenceValidator`, `CalculationDispatcher`, all 6 real calculators, real `SystemVariableAggregator`, real (now-fixed) `ResultPersister` — with only the JPA repositories mocked, since this engine has no DB-backed integration test harness yet (unlike the old engine's pure-POJO `PayrollSimulatorServiceTunisiaConfigTest`, this engine's classes are Spring `@Service` beans with repository dependencies).

**Correction to the spec's stated acceptance bar**: the spec says net/loadedCost should both match 415.40/543.17. That's only half right. Net (415.40) matches, because it depends only on gross, employee-side charges, and taxes — identical inputs between the two engines once rates are corrected. **LoadedCost cannot and must not match 543.17** — that figure comes from the old model, which has no TFP/FOPROLOS concept at all (confirmed in the spec's own "out of scope" section: "TFP and FOPROLOS exist only in the new model, with no old-model equivalent"). Once bug 3 is fixed, the new model's loadedCost is *correctly higher* than the old model's: `462 + CNSS_PATRONAL(76.55) + TFP(9.24) + FOPROLOS(4.62) + AT_PATRONAL(4.62) = 557.03`. Asserting 543.17 here would mean TFP/FOPROLOS silently dropped out again — the exact bug this task fixes. The test below asserts the correct 557.03, not the spec's stated 543.17.

- [ ] **Step 1: Write the test**

```java
package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.engine.TopologicalEvaluator;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.payroll.calculator.*;
import com.daf360.payroll.modules.payroll.dto.RunPayrollRequest;
import com.daf360.payroll.modules.payroll.dto.RunPayrollResponse;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollParamSetRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollRubriqueDefRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Acceptance test for the new-model engine fix: running it with the same inputs as the
 * old model's already-validated Tunisia reference scenario (gross=462, CDI, single, 0
 * children, no benefits) must produce the SAME net (415.40) as
 * PayrollSimulatorServiceTunisiaConfigTest's reference (see
 * docs/PAYROLL_CONFIG_VERIFICATION.md) — but a DIFFERENT, correctly higher, loadedCost
 * (557.03, not the old model's 543.17), since the new model correctly includes
 * TFP/FOPROLOS employer charges the old model has no concept of at all.
 */
@ExtendWith(MockitoExtension.class)
class PayrollOrchestratorTunisiaEngineFixTest {

    @Mock private PayrollCountryRepository countryRepo;
    @Mock private PayrollParamSetRepository paramSetRepo;
    @Mock private PayrollRubriqueDefRepository rubriqueRepo;
    @Mock private PayrollResultRepository resultRepo;
    @Mock private EmployeePayrollConfigRepository employeeConfigRepo;
    @Mock private ParameterSetService parameterSetService;
    @Mock private ForexSnapshotService forexService; // concrete @Service class, not an interface — confirmed by reading it

    private static final String TUNISIA_PARAMETERS = "{"
        + "\"salaire_base_tn\":2500.0,\"taux_cnss_sal_tn\":9.68,\"taux_cnss_empl_tn\":16.57,"
        + "\"plafond_cnss_mens_tn\":6570.0,\"taux_tfp_tn\":2.0,\"taux_foprolos_tn\":1.0,"
        + "\"taux_at_tn\":1.0,\"taux_css_tn\":0.5,\"seuil_css_annuel_tn\":6000.0,"
        + "\"abattement_rate_tn\":0.10,\"abattement_cap_tn\":2000,\"family_married_tn\":300,"
        + "\"family_per_child_tn\":100,\"family_max_children_tn\":4,"
        + "\"bareme_irpp_tn\":[{\"min\":0,\"max\":5000,\"rate\":0},"
        + "{\"min\":5000,\"max\":10000,\"rate\":15},{\"min\":10000,\"max\":20000,\"rate\":25},"
        + "{\"min\":20000,\"max\":30000,\"rate\":30},{\"min\":30000,\"max\":40000,\"rate\":33},"
        + "{\"min\":40000,\"max\":50000,\"rate\":36},{\"min\":50000,\"max\":70000,\"rate\":38},"
        + "{\"min\":70000,\"max\":null,\"rate\":40}]}";

    private static PayrollRubriqueDef rubrique(String code, int strate, String nature, String mode,
                                                String assietteCode, String paramKeyTaux,
                                                String paramKeyPlafond, String paramKeyBareme,
                                                String formula, String payer, int order) {
        PayrollRubriqueDef r = new PayrollRubriqueDef();
        r.setCode(code);
        r.setLabelFr(code);
        r.setStrate(strate);
        r.setNature(nature);
        r.setModeCalcul(mode);
        r.setAssietteCode(assietteCode);
        r.setParamKeyTaux(paramKeyTaux);
        r.setParamKeyPlafond(paramKeyPlafond);
        r.setParamKeyBareme(paramKeyBareme);
        r.setFormulaExpression(formula);
        r.setPeriodicite("MENSUEL");
        r.setDisplayOrder(order);
        r.setActive(true);
        r.setPayer(payer);
        return r;
    }

    private List<PayrollRubriqueDef> tunisiaRubriques() {
        return List.of(
            rubrique("SALAIRE_BASE", 1, "GAIN", "MONTANT_FIXE", null, "salaire_base_tn", null, null, null, null, 10),
            rubrique("CNSS_SALARIE", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_cnss_sal_tn", "plafond_cnss_mens_tn", null, null, "EMPLOYEE", 20),
            rubrique("CNSS_PATRONAL", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_cnss_empl_tn", "plafond_cnss_mens_tn", null, null, "EMPLOYER", 25),
            rubrique("TFP", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_tfp_tn", null, null, null, "EMPLOYER", 30),
            rubrique("FOPROLOS", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_foprolos_tn", null, null, null, "EMPLOYER", 35),
            rubrique("AT_PATRONAL", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_at_tn", null, null, null, "EMPLOYER", 40),
            rubrique("ASSIETTE_IRPP", 4, "TAXE", "FORMULE", null, null, null, null,
                "T(Math).ceil(T(Math).max(((BRUT + AVANTAGES - CNSS_SALARIE) * 12) - T(Math).min(((BRUT + AVANTAGES - CNSS_SALARIE) * 12) * abattement_rate_tn, abattement_cap_tn) - ((IS_MARRIED * family_married_tn) + (T(Math).min(CHILDREN_COUNT, family_max_children_tn) * family_per_child_tn)), 0)) / 12",
                null, 45),
            rubrique("IRPP", 5, "TAXE", "ANNUALISE_BAREME", "STRATE_4", null, null, "bareme_irpp_tn", null, null, 50),
            rubrique("CSS", 5, "TAXE", "TAUX_PCT", "STRATE_4", "taux_css_tn", null, null, null, null, 60)
        );
    }

    @Test
    void tunisiaGross462_singleNoChildren_matchesOldModelReference() {
        PayrollCountry country = new PayrollCountry();
        country.setId(7L);
        country.setPaysId(179L);
        when(countryRepo.findByPaysIdAndActiveTrue(179L)).thenReturn(Optional.of(country));

        PayrollParamSet paramSet = new PayrollParamSet();
        paramSet.setId(7L);
        paramSet.setCountryId(7L);
        paramSet.setStatus("ACTIVE");
        paramSet.setVersionNumber(1);
        paramSet.setEffectiveDate(LocalDate.of(2026, 1, 1));
        paramSet.setParameters(TUNISIA_PARAMETERS);
        when(paramSetRepo.findByCountryIdAndStatus(7L, "ACTIVE")).thenReturn(Optional.of(paramSet));

        when(rubriqueRepo.findByCountryIdAndActiveTrue(7L)).thenReturn(tunisiaRubriques());

        EmployeePayrollConfig config = new EmployeePayrollConfig();
        config.setCurrentGrossSalary(new BigDecimal("462"));
        config.setIsMarried(false);
        config.setChildrenCount(0);
        when(employeeConfigRepo.findByProfileUserId(1L)).thenReturn(Optional.of(config));

        when(resultRepo.save(any())).thenAnswer(inv -> {
            PayrollResult r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        // Bridge is best-effort and swallows failures (PayrollOrchestrator#injectChargeVariables'
        // own try/catch) — leave parameterSetService unstubbed so it throws, proving the bridge's
        // output plays no role in this test's assertions.
        when(parameterSetService.loadActiveEntity(179L)).thenThrow(new IllegalStateException("no old-model set"));

        PayrollOrchestrator orchestrator = new PayrollOrchestrator(
            new ContextLoader(countryRepo, paramSetRepo, new ObjectMapper()),
            new SequenceValidator(),
            new CalculationDispatcher(List.of(
                new MontantFixeCalculator(), new TauxPctCalculator(), new BaremeProgressifCalculator(),
                new AnnualiseBaremeCalculator(), new FormuleCalculator(), new NewtonRaphsonCalculator())), // no-arg constructor — confirmed no injected fields
            new SystemVariableAggregator(),
            new ResultPersister(resultRepo, new ObjectMapper()),
            forexService, // mocked; PayrollOrchestrator#run wraps this call in a swallowed try/catch regardless
            rubriqueRepo, countryRepo, new TopologicalEvaluator(null), parameterSetService, employeeConfigRepo);

        RunPayrollRequest request = new RunPayrollRequest();
        request.setEmployeeId(1L);
        request.setPaysId(179L);
        request.setPeriodYear(2026);
        request.setPeriodMonth(1);
        request.setContractTypeCode("CDI");

        RunPayrollResponse response = orchestrator.run(request);

        // NET matches the old model's validated reference exactly: net only depends on
        // gross, employee-side charges, and taxes — none of which differ between the two
        // engines once rates are corrected (Task 1).
        assertThat(response.getAggregateNet()).isCloseTo(new BigDecimal("415.40"), within(new BigDecimal("0.01")));

        // LOADED COST must NOT match the old model's 543.17 — that figure structurally
        // excludes TFP and FOPROLOS (no old-model equivalent exists at all, per the spec's
        // own "out of scope" section). This is the actual bug-3 regression bar: gross +
        // CNSS_PATRONAL(16.57%) + TFP(2%) + FOPROLOS(1%) + AT_PATRONAL(1%) on a 462 gross
        // = 462 + 76.55 + 9.24 + 4.62 + 4.62 = 557.03. If this test ever asserts 543.17
        // instead, that is the OLD bug silently reintroduced (TFP/FOPROLOS dropped again).
        assertThat(response.getAggregateEmployerCharges()).isCloseTo(new BigDecimal("95.03"), within(new BigDecimal("0.01")));
        assertThat(response.getLoadedCost()).isCloseTo(new BigDecimal("557.03"), within(new BigDecimal("0.01")));
    }
}
```

**Three mechanical facts this plan already confirmed by reading the real classes, so the test above reflects them directly instead of leaving them as runtime guesses:**
1. `ForexSnapshotService` is a concrete `@Service` class (not an interface) — the test mocks it with `@Mock private ForexSnapshotService forexService` and passes it directly; no stubbing needed since `PayrollOrchestrator.run()` wraps the call in a swallowed try/catch.
2. `NewtonRaphsonCalculator` has no injected fields and no declared constructor (plain implicit no-arg) — instantiated as `new NewtonRaphsonCalculator()`, not with a `FormuleCalculator` argument.
3. `TopologicalEvaluator(FormulaEvaluatorService)` is never actually invoked in this test: `PayrollOrchestrator.injectChargeVariables()` calls `parameterSetService.loadActiveEntity(paysId)` (stubbed to throw) before it ever reaches `topologicalEvaluator.evaluate(...)`, and the whole method is wrapped in a try/catch — so `new TopologicalEvaluator(null)` is safe here.

- [ ] **Step 2: Run it**

```bash
mvn -q -o test -Dtest=PayrollOrchestratorTunisiaEngineFixTest
```
Expected: `Tests run: 1, Failures: 0, Errors: 0`. If it fails on the numeric assertions, the formula string in Task 1's migration and this test must match EXACTLY — re-check for a transcription typo between them before suspecting the math itself (the math was derived and is believed correct, but this is the actual proof).

- [ ] **Step 3: Add the Egypt structural-only check and the full regression suite in one more test method, same file**

```java
    @Test
    void egyptIncomeTax_noLongerHardZero_butNotClaimedCorrect() {
        PayrollCountry country = new PayrollCountry();
        country.setId(8L);
        country.setPaysId(53L);
        when(countryRepo.findByPaysIdAndActiveTrue(53L)).thenReturn(Optional.of(country));

        PayrollParamSet paramSet = new PayrollParamSet();
        paramSet.setId(8L);
        paramSet.setCountryId(8L);
        paramSet.setStatus("ACTIVE");
        paramSet.setVersionNumber(1);
        paramSet.setEffectiveDate(LocalDate.of(2026, 1, 1));
        paramSet.setParameters("{\"salaire_base_eg\":15000.0,\"taux_si_sal_eg\":11.0,"
            + "\"taux_si_empl_eg\":18.75,\"plafond_si_mens_eg\":10900.0,\"taux_shif_eg\":3.0,"
            + "\"bareme_impot_eg\":[{\"min\":0,\"max\":40000,\"rate\":0},"
            + "{\"min\":40000,\"max\":55000,\"rate\":10},{\"min\":55000,\"max\":70000,\"rate\":15}]}");
        when(paramSetRepo.findByCountryIdAndStatus(8L, "ACTIVE")).thenReturn(Optional.of(paramSet));

        when(rubriqueRepo.findByCountryIdAndActiveTrue(8L)).thenReturn(List.of(
            rubrique("SALAIRE_BASE", 1, "GAIN", "MONTANT_FIXE", null, "salaire_base_eg", null, null, null, null, 10),
            rubrique("SI_SALARIE", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_si_sal_eg", "plafond_si_mens_eg", null, null, "EMPLOYEE", 20),
            rubrique("SI_PATRONAL", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_si_empl_eg", "plafond_si_mens_eg", null, null, "EMPLOYER", 25),
            rubrique("SHIF_PATRONAL", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_shif_eg", null, null, null, "EMPLOYER", 30),
            rubrique("ASSIETTE_INCOME_TAX", 4, "TAXE", "FORMULE", null, null, null, null,
                "T(Math).max((BRUT + AVANTAGES - SI_SALARIE) * 12, 0) / 12", null, 35),
            rubrique("INCOME_TAX", 5, "TAXE", "ANNUALISE_BAREME", "STRATE_4", null, null, "bareme_impot_eg", null, null, 40)
        ));

        EmployeePayrollConfig config = new EmployeePayrollConfig();
        config.setCurrentGrossSalary(new BigDecimal("15000"));
        config.setIsMarried(false);
        config.setChildrenCount(0);
        when(employeeConfigRepo.findByProfileUserId(2L)).thenReturn(Optional.of(config));

        when(resultRepo.save(any())).thenAnswer(inv -> {
            PayrollResult r = inv.getArgument(0);
            r.setId(2L);
            return r;
        });
        when(parameterSetService.loadActiveEntity(53L)).thenThrow(new IllegalStateException("no old-model set"));

        PayrollOrchestrator orchestrator = new PayrollOrchestrator(
            new ContextLoader(countryRepo, paramSetRepo, new ObjectMapper()),
            new SequenceValidator(),
            new CalculationDispatcher(List.of(
                new MontantFixeCalculator(), new TauxPctCalculator(), new BaremeProgressifCalculator(),
                new AnnualiseBaremeCalculator(), new FormuleCalculator(), new NewtonRaphsonCalculator())),
            new SystemVariableAggregator(),
            new ResultPersister(resultRepo, new ObjectMapper()),
            forexService,
            rubriqueRepo, countryRepo, new TopologicalEvaluator(null), parameterSetService, employeeConfigRepo);

        RunPayrollRequest request = new RunPayrollRequest();
        request.setEmployeeId(2L);
        request.setPaysId(53L);
        request.setPeriodYear(2026);
        request.setPeriodMonth(1);
        request.setContractTypeCode("CDI");

        RunPayrollResponse response = orchestrator.run(request);

        // Structural check ONLY — Egypt's rates/formula are explicitly unvalidated.
        // 15000 gross clears the 40000/year (≈3333/month) first bracket easily, so this
        // should be comfortably non-zero; not asserting a specific figure.
        assertThat(response.getAggregateIrpp()).isNull(); // aggregateIrpp is hardcoded to "IRPP", pre-existing, out of scope — see plan notes.
        assertThat(response.getStrate5()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.getAggregateNet()).isLessThan(response.getAggregateGross());
    }
```

- [ ] **Step 4: Run both tests**

```bash
mvn -q -o test -Dtest=PayrollOrchestratorTunisiaEngineFixTest
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Run the full module test suite to confirm no regressions**

```bash
mvn -q -o test
```
Expected: all tests pass (including the pre-existing `PayrollOrchestratorBaseSalaryTest` and the old-model `PayrollSimulatorServiceTunisiaConfigTest`, both untouched by this plan).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/daf360/payroll/modules/payroll/orchestrator/PayrollOrchestratorTunisiaEngineFixTest.java
git commit -m "test(payroll): acceptance test — new engine matches validated Tunisia reference

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Delete dead code — `SystemVariableAggregator.populateResult()`

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/payroll/orchestrator/SystemVariableAggregator.java`

- [ ] **Step 1: Confirm zero callers one more time (belt and suspenders before deleting)**

```bash
cd daf360-payroll-service
grep -rn "populateResult" src/
```
Expected: only the method's own definition in `SystemVariableAggregator.java`, no call sites.

- [ ] **Step 2: Delete the method**

Remove this entire method from `SystemVariableAggregator.java`:
```java
    /**
     * Writes the final strate totals and aggregate KPIs into the PayrollResult entity.
     * The entity must already be managed (i.e. saved within the current transaction)
     * so that changes are tracked by JPA dirty-checking.
     */
    public void populateResult(PayrollResult result,
                                List<RubriqueResultItem> items,
                                ExecutionContext context) {
        BigDecimal strate1 = context.getVariable("STRATE_1");
        BigDecimal strate2 = context.getVariable("STRATE_2");
        BigDecimal strate3 = context.getVariable("STRATE_3");
        BigDecimal strate4 = context.getVariable("STRATE_4");
        BigDecimal strate5 = context.getVariable("STRATE_5");

        result.setStrate1(strate1);
        result.setStrate2(strate2);
        result.setStrate3(strate3);
        result.setStrate4(strate4);
        result.setStrate5(strate5);

        result.setAggregateGross(strate1.add(strate2));
        result.setAggregateNet(strate5);
        // Strate 3 represents employee charges; used as employer charges proxy
        result.setAggregateEmployerCharges(strate3);
        // IRPP is set by the IRPP rubrique and stored in context under "IRPP"
        result.setAggregateIrpp(context.getVariable("IRPP"));
        result.setLoadedCost(strate1.add(strate2).add(strate3));
    }
```

After deleting it, remove the now-unused `PayrollResult` import if `aggregate()` doesn't reference it elsewhere (it doesn't — `aggregate()`'s signature is `(List<RubriqueResultItem>, ExecutionContext)`, no `PayrollResult` parameter).

- [ ] **Step 3: Compile and run the full suite**

```bash
mvn -q -o compile -DskipTests
mvn -q -o test
```
Expected: both clean (no unused-import compile warnings-as-errors configured in this project, and no test depended on the deleted method — confirmed zero callers in Step 1, including tests).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/payroll/orchestrator/SystemVariableAggregator.java
git commit -m "chore(payroll): delete dead SystemVariableAggregator.populateResult()

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: New-engine rubrique admin — `payer` field + `SYSTEM_VARIABLES` additions

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/admin/dto/SaveEngineRubriqueRequest.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/admin/service/EngineConfigService.java`
- Test: `daf360-payroll-service/src/test/java/com/daf360/payroll/modules/admin/service/EngineConfigServicePayerTest.java` (create if no existing test file for this service covers `apply()`'s validation — check first: `find src/test -iname "EngineConfigServiceTest.java"`; if one exists, add to it instead of creating a new file, following its existing setup pattern)

So a rubrique created or edited through this (currently UI-unreachable, but real) admin path isn't silently miscounted the same way TFP/FOPROLOS were — and so `IS_MARRIED`/`CHILDREN_COUNT`/`ASSIETTE_IRPP`'s own formula can pass this service's formula-name validation if edited later.

- [ ] **Step 1: Check for an existing test file to extend**

```bash
find src/test -iname "EngineConfigServiceTest.java"
```
If found, read it fully first to match its exact mocking/setup conventions before writing Step 5's test — do not duplicate a different style.

- [ ] **Step 2: Add `payer` to the DTO**

Current:
```java
public record SaveEngineRubriqueRequest(
        String code,
        String labelFr,
        String labelEn,
        Integer strate,
        String nature,
        String modeCalcul,
        String assietteCode,
        String paramKeyTaux,
        String paramKeyPlafond,
        String paramKeyBareme,
        String formulaExpression,
        String contractTypeFilter,
        String periodicite,
        Boolean prorataApplicable,
        Integer displayOrder,
        Boolean active
) {}
```

Replace with:
```java
public record SaveEngineRubriqueRequest(
        String code,
        String labelFr,
        String labelEn,
        Integer strate,
        String nature,
        String modeCalcul,
        String assietteCode,
        String paramKeyTaux,
        String paramKeyPlafond,
        String paramKeyBareme,
        String formulaExpression,
        String contractTypeFilter,
        String periodicite,
        Boolean prorataApplicable,
        Integer displayOrder,
        Boolean active,
        /** EMPLOYEE | EMPLOYER | null — required when nature=COTISATION, ignored otherwise. */
        String payer
) {}
```

- [ ] **Step 3: Add `IS_MARRIED`/`CHILDREN_COUNT` to `SYSTEM_VARIABLES`**

Current:
```java
    private static final Set<String> SYSTEM_VARIABLES = Set.of(
            "JOURS_OUVRES", "MOIS", "ANNEE",
            "STRATE_1", "STRATE_2", "STRATE_3", "STRATE_4", "STRATE_5",
            "BRUT", "AVANTAGES", "CHARGES_SAL", "NET_IMPOSABLE", "NET_A_PAYER", "SELF");
```

Replace with:
```java
    private static final Set<String> SYSTEM_VARIABLES = Set.of(
            "JOURS_OUVRES", "MOIS", "ANNEE",
            "STRATE_1", "STRATE_2", "STRATE_3", "STRATE_4", "STRATE_5",
            "BRUT", "AVANTAGES", "CHARGES_SAL", "NET_IMPOSABLE", "NET_A_PAYER", "SELF",
            // Seeded by PayrollOrchestrator#applyEmployeeBaseSalary from the employee's
            // own employee_payroll_config row — see 2026-11-06-engine-run-irpp-css-net-fix.
            "IS_MARRIED", "CHILDREN_COUNT");
```

- [ ] **Step 4: Validate and apply `payer` in `apply()`**

Current:
```java
        r.setLabelFr(label);
        r.setLabelEn(trim(req.labelEn()));
        r.setStrate(strate);
        r.setNature(nature.toUpperCase());
        r.setModeCalcul(mode);
        r.setAssietteCode(assiette);
        r.setParamKeyTaux(taux);
        r.setParamKeyPlafond(plafond);
        r.setParamKeyBareme(bareme);
        r.setFormulaExpression(formula);
        r.setContractTypeFilter(contracts);
        r.setPeriodicite(periodicite == null ? "MENSUEL" : periodicite.toUpperCase());
        r.setProrataApplicable(Boolean.TRUE.equals(req.prorataApplicable()));
        r.setDisplayOrder(order);
        r.setActive(req.active() == null || req.active());
    }
```

Replace with:
```java
        String payer = trim(req.payer());
        if (payer != null) payer = payer.toUpperCase();
        if ("COTISATION".equals(nature.toUpperCase())) {
            if (payer == null) throw new IllegalArgumentException("Nature COTISATION : payeur (EMPLOYEE/EMPLOYER) obligatoire");
        } else if (payer != null) {
            throw new IllegalArgumentException("Payeur uniquement pour la nature COTISATION");
        }
        if (payer != null && !Set.of("EMPLOYEE", "EMPLOYER").contains(payer)) {
            throw new IllegalArgumentException("Payeur invalide : EMPLOYEE ou EMPLOYER");
        }

        r.setLabelFr(label);
        r.setLabelEn(trim(req.labelEn()));
        r.setStrate(strate);
        r.setNature(nature.toUpperCase());
        r.setModeCalcul(mode);
        r.setAssietteCode(assiette);
        r.setParamKeyTaux(taux);
        r.setParamKeyPlafond(plafond);
        r.setParamKeyBareme(bareme);
        r.setFormulaExpression(formula);
        r.setContractTypeFilter(contracts);
        r.setPeriodicite(periodicite == null ? "MENSUEL" : periodicite.toUpperCase());
        r.setProrataApplicable(Boolean.TRUE.equals(req.prorataApplicable()));
        r.setDisplayOrder(order);
        r.setActive(req.active() == null || req.active());
        r.setPayer(payer);
    }
```

- [ ] **Step 5: Write a test for the new validation** (add to the existing test file found in Step 1, or create `EngineConfigServicePayerTest.java` matching its mocking conventions if none exists — in either case, the two cases to cover are:)

```java
// (illustrative shape — fit into whichever file/setup Step 1 found; mirror its existing
// @Mock/@InjectMocks or manual-construction pattern exactly, do not invent a new one)

@Test
void cotisationWithoutPayer_isRejected() {
    SaveEngineRubriqueRequest req = new SaveEngineRubriqueRequest(
        "TEST_COTIS", "Test", null, 3, "COTISATION", "TAUX_PCT", "STRATE_1",
        "some_key", null, null, null, null, null, false, 0, true, null /* payer */);

    assertThatThrownBy(() -> service.createRubrique(179L, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payeur");
}

@Test
void gainWithPayer_isRejected() {
    SaveEngineRubriqueRequest req = new SaveEngineRubriqueRequest(
        "TEST_GAIN", "Test", null, 1, "GAIN", "MONTANT_FIXE", null,
        "some_key", null, null, null, null, null, false, 0, true, "EMPLOYEE");

    assertThatThrownBy(() -> service.createRubrique(179L, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Payeur uniquement");
}
```

- [ ] **Step 6: Run the test(s)**

```bash
mvn -q -o test -Dtest=EngineConfigServiceTest   # or EngineConfigServicePayerTest, whichever Step 1/5 landed on
```
Expected: new cases pass; no existing case in that file broken (a `COTISATION` rubrique created without a `payer` in any PRE-EXISTING test in that file would now fail — if so, add `, "EMPLOYEE"` or `"EMPLOYER"` as appropriate to that test's request construction, matching what the rubrique actually represents).

- [ ] **Step 7: Compile the whole module and run the full suite once more**

```bash
mvn -q -o compile -DskipTests
mvn -q -o test
```
Expected: clean.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/admin/dto/SaveEngineRubriqueRequest.java src/main/java/com/daf360/payroll/modules/admin/service/EngineConfigService.java src/test/java/com/daf360/payroll/modules/admin/service/
git commit -m "feat(admin): validate payer on new-engine rubrique create/update

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Manual verification (not a subagent task — do this yourself at the end)

- [ ] Rebuild and restart the local `daf360-payroll-backend-dev` container so it picks up all of the above.
- [ ] Via the actual UI ("Calcul de paie"), run the engine for a real Tunisia employee with a configured gross salary.
- [ ] Open "Résultats de paie" for that employee and confirm: IRPP and CSS are non-zero and plausible, "Coût employeur" visibly exceeds gross by more than just CNSS+AT (TFP+FOPROLOS now included), "Net à payer" is less than gross and greater than net-after-just-employee-CNSS (i.e., it reads as gross minus charges minus tax, not as the raw tax total).
- [ ] Report back to the user with the actual numbers observed, and remind them Egypt's rate values remain explicitly unvalidated per the spec's scope decision.

---

## Self-review (spec coverage, placeholders, type consistency)

**Spec coverage**: bug 1 (strate-4 gap) → Task 1 (rubrique rows) + Task 4/5 (variables the formula needs). Bug 2 (net wiring) → Task 6. Bug 3 (employer-cost undercount) → Task 6. Bug 4 (employee/employer mixing) → Task 1 (`payer` column) + Task 6 (filtering by it). Bug 5 (dead code) → Task 8. Bug 6 (stale Tunisia rates) → Task 1. Admin-path awareness → Task 9. Acceptance/regression/Egypt-structural testing → Task 7.

**Placeholder scan**: none found — every task has real file paths (all verified to exist by reading them), real current-code "before" blocks (copied from the actual files read during planning), real new code, real commands. The three mechanical facts noted before Task 7's test (ForexSnapshotService being a concrete class, NewtonRaphsonCalculator's no-arg constructor, TopologicalEvaluator's null-safety here) were resolved by directly reading those classes during planning, not left as runtime guesses.

**Type consistency**: `payer` is `String` (matching `nature`/`modeCalcul`'s existing plain-`String` convention on `PayrollRubriqueDef`, confirmed by reading the entity — no enum pattern exists anywhere in this entity to match instead) end to end: entity field (Task 2) → DTO field (Task 3) → `ResultPersister` filter (Task 6) → admin DTO/validation (Task 9) → test fixtures (Task 7) all use the identical `"EMPLOYEE"`/`"EMPLOYER"` string literals.

**Spec correction found during planning**: the spec's Testing section states the acceptance bar as "net/loadedCost matching 415.40/543.17." This is only correct for net. loadedCost cannot match 543.17 once bug 3 is actually fixed — 543.17 is the OLD model's figure, which has no TFP/FOPROLOS at all; the corrected NEW model's loadedCost for the same scenario is 557.03 (462 + CNSS_PATRONAL + TFP + FOPROLOS + AT_PATRONAL). Task 7 asserts the corrected value and documents why. The spec document itself was not edited (out of scope for a plan to change an approved spec) — flagging here for the user/orchestrator to decide whether to amend the spec's wording to match.
