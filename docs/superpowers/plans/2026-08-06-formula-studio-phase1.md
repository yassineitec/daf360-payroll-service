# Payroll Formula Studio — Phase 1: Backend Foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POURCENTAGE_PLAFONNE` (capped-percentage) as a new calc mode to the simulation engine, and fix a pre-existing bug where the `PayrollRubrique` JPA entity maps to the wrong DB table after the V9 migration.

**Architecture:** The simulation engine (`PayrollSimulatorService`) reads rubriques through `ParameterSetService.loadRubriques()` → `PayrollRubriqueRepository`. The V9 Flyway migration renamed the original `payroll_rubriques` table to `payroll_rubriques_legacy` and created a new `payroll_rubriques` with a different schema. The `PayrollRubrique` entity still declares `@Table(name = "payroll_rubriques")`, which now points to the V9-schema table — silently breaking any rubrique that gets saved via the admin API. This plan fixes the table mapping first, then adds `POURCENTAGE_PLAFONNE` on top of the corrected foundation.

**Tech Stack:** Spring Boot 3.x, JPA/Hibernate, Flyway, SQL Server, Java records for DTOs

---

## File Map

| Action | Path |
|--------|------|
| Modify | `src/main/java/com/daf360/payroll/modules/parameterset/entity/PayrollRubrique.java` |
| Create | `src/main/resources/db/migration/V16__add_pourcentage_plafonne.sql` |
| Modify | `src/main/java/com/daf360/payroll/engine/PayrollSimulatorService.java` |
| Modify | `src/main/java/com/daf360/payroll/modules/parameterset/dto/SavePayrollRubriqueRequest.java` |
| Modify | `src/main/java/com/daf360/payroll/modules/parameterset/dto/PayrollRubriqueDto.java` |
| Modify | `src/main/java/com/daf360/payroll/modules/parameterset/service/ParameterSetService.java` |

---

### Task 1: Fix the PayrollRubrique entity's table mapping

**Files:**
- Modify: `src/main/java/com/daf360/payroll/modules/parameterset/entity/PayrollRubrique.java:11`

The V9 migration renamed `payroll_rubriques` → `payroll_rubriques_legacy`, and the entity was never updated. Any query from the simulation engine runs against the V9-schema table which has no `parameter_set_id` or `calc_mode` columns, causing a SQL runtime error that silently discards all rubriques from every simulation.

- [ ] **Step 1: Read the entity to confirm line 11**

```
Read: src/main/java/com/daf360/payroll/modules/parameterset/entity/PayrollRubrique.java
Confirm that line 11 reads: @Table(name = "payroll_rubriques")
```

- [ ] **Step 2: Change the table name**

In `PayrollRubrique.java`, change line 11:

```java
// BEFORE:
@Table(name = "payroll_rubriques")

// AFTER:
@Table(name = "payroll_rubriques_legacy")
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/parameterset/entity/PayrollRubrique.java
git commit -m "fix(parameterset): map PayrollRubrique entity to correct legacy table after V9 migration"
```

---

### Task 2: Flyway V16 — extend the calc_mode constraint and add cap_amount column

**Files:**
- Create: `src/main/resources/db/migration/V16__add_pourcentage_plafonne.sql`

**Background:** V8 created `CK_rubrique_calc_mode` on `payroll_rubriques` with 4 allowed values. V9 renamed the table to `payroll_rubriques_legacy` but did not rename this constraint (it kept its original name). To allow the new `POURCENTAGE_PLAFONNE` value, we must drop the old constraint and re-add it with 5 values. We also add the `cap_amount` column that the new mode reads from.

- [ ] **Step 1: Create the migration file**

Create `src/main/resources/db/migration/V16__add_pourcentage_plafonne.sql` with this exact content:

```sql
-- V16__add_pourcentage_plafonne.sql
-- Extends payroll_rubriques_legacy (the V8-schema simulation table) to support
-- POURCENTAGE_PLAFONNE calc mode: amount = min(gross, cap_amount) × rate.
-- Each block is idempotent for safe re-run.

-- ── 1. Drop old calc_mode constraint (4 values) ───────────────────────────────
IF EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'CK_rubrique_calc_mode'
      AND parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
)
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    DROP CONSTRAINT [CK_rubrique_calc_mode];
GO

-- ── 2. Re-add with 5 values ───────────────────────────────────────────────────
IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'CK_rubrique_calc_mode'
      AND parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
)
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    ADD CONSTRAINT [CK_rubrique_calc_mode]
        CHECK ([calc_mode] IN (
            'FIXE_MENSUEL',
            'FIXE_JOURNALIER',
            'POURCENTAGE_BRUT',
            'POURCENTAGE_CHARGES',
            'POURCENTAGE_PLAFONNE'
        ));
GO

-- ── 3. Add cap_amount column ───────────────────────────────────────────────────
-- Used by POURCENTAGE_PLAFONNE: effective base = min(gross, cap_amount).
-- NULL means no cap (behaviour identical to POURCENTAGE_BRUT).
IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.payroll_rubriques_legacy')
      AND name = 'cap_amount'
)
    ALTER TABLE [dbo].[payroll_rubriques_legacy]
    ADD [cap_amount] NUMERIC(18, 4) NULL;
GO
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V16__add_pourcentage_plafonne.sql
git commit -m "feat(db): V16 — add cap_amount column and extend calc_mode constraint for POURCENTAGE_PLAFONNE"
```

---

### Task 3: Add capAmount field to PayrollRubrique entity

**Files:**
- Modify: `src/main/java/com/daf360/payroll/modules/parameterset/entity/PayrollRubrique.java`

- [ ] **Step 1: Add the field after the existing `rate` field (around line 40)**

Open the file. After the `rate` field:

```java
@Column(name = "rate", precision = 10, scale = 6)
private BigDecimal rate;
```

Add:

```java
@Column(name = "cap_amount", precision = 18, scale = 4)
private BigDecimal capAmount;
```

The full field block should look like:

```java
@Column(name = "amount", precision = 18, scale = 4)
private BigDecimal amount;

@Column(name = "rate", precision = 10, scale = 6)
private BigDecimal rate;

@Column(name = "cap_amount", precision = 18, scale = 4)
private BigDecimal capAmount;

@Column(name = "employer_share_pct", nullable = false, precision = 10, scale = 6)
private BigDecimal employerSharePct = BigDecimal.ZERO;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/parameterset/entity/PayrollRubrique.java
git commit -m "feat(parameterset): add capAmount field to PayrollRubrique entity"
```

---

### Task 4: Add POURCENTAGE_PLAFONNE logic to PayrollSimulatorService

**Files:**
- Modify: `src/main/java/com/daf360/payroll/engine/PayrollSimulatorService.java:194-212`

The `computeRubriqueAmount()` method has a `switch` on `calcMode`. It currently handles 4 cases and falls through to `default -> BigDecimal.ZERO`. Add the 5th case before the default.

- [ ] **Step 1: Locate the switch block (lines ~194–213)**

The current block looks like:

```java
return switch (r.getCalcMode()) {
    case "FIXE_MENSUEL" ->
            r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
    case "FIXE_JOURNALIER" ->
            r.getAmount() != null
                    ? r.getAmount()
                      .multiply(BigDecimal.valueOf(joursTravailes))
                      .divide(BigDecimal.valueOf(STANDARD_WORKING_DAYS), SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
    case "POURCENTAGE_BRUT" ->
            r.getRate() != null
                    ? gross.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
    case "POURCENTAGE_CHARGES" ->
            r.getRate() != null
                    ? totalBaseCharges.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
    default -> BigDecimal.ZERO;
};
```

- [ ] **Step 2: Add the POURCENTAGE_PLAFONNE case before `default`**

Replace the entire switch block with:

```java
return switch (r.getCalcMode()) {
    case "FIXE_MENSUEL" ->
            r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
    case "FIXE_JOURNALIER" ->
            r.getAmount() != null
                    ? r.getAmount()
                      .multiply(BigDecimal.valueOf(joursTravailes))
                      .divide(BigDecimal.valueOf(STANDARD_WORKING_DAYS), SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
    case "POURCENTAGE_BRUT" ->
            r.getRate() != null
                    ? gross.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
    case "POURCENTAGE_CHARGES" ->
            r.getRate() != null
                    ? totalBaseCharges.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
    case "POURCENTAGE_PLAFONNE" -> {
        if (r.getRate() == null) yield BigDecimal.ZERO;
        BigDecimal effectiveBase = (r.getCapAmount() != null)
                ? gross.min(r.getCapAmount())
                : gross;
        yield effectiveBase.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP);
    }
    default -> BigDecimal.ZERO;
};
```

- [ ] **Step 3: Verify the file compiles**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS with no errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/daf360/payroll/engine/PayrollSimulatorService.java
git commit -m "feat(engine): add POURCENTAGE_PLAFONNE calc mode — min(gross, cap) × rate"
```

---

### Task 5: Add capAmount to SavePayrollRubriqueRequest DTO

**Files:**
- Modify: `src/main/java/com/daf360/payroll/modules/parameterset/dto/SavePayrollRubriqueRequest.java`

- [ ] **Step 1: Read the current record**

Open `SavePayrollRubriqueRequest.java`. It is a Java record. The current components are:
`code, labelFr, labelEn, nature, calcMode, amount, rate, employerSharePct, employeeSharePct, isSubjectToSocialCharges, isSubjectToIrpp, direction, contractTypes, isActive`

- [ ] **Step 2: Add capAmount after rate**

Replace the file content with:

```java
package com.daf360.payroll.modules.parameterset.dto;

import java.math.BigDecimal;

public record SavePayrollRubriqueRequest(
    String code,
    String labelFr,
    String labelEn,
    String nature,
    String calcMode,
    BigDecimal amount,
    BigDecimal rate,
    BigDecimal capAmount,
    BigDecimal employerSharePct,
    BigDecimal employeeSharePct,
    Boolean isSubjectToSocialCharges,
    Boolean isSubjectToIrpp,
    String direction,
    String contractTypes,
    Boolean isActive
) {}
```

- [ ] **Step 3: Compile to check for errors**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS. If there are errors, they will be in callers that construct `SavePayrollRubriqueRequest` directly — check the test directory if any exists.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/parameterset/dto/SavePayrollRubriqueRequest.java
git commit -m "feat(parameterset): add capAmount to SavePayrollRubriqueRequest"
```

---

### Task 6: Add capAmount to PayrollRubriqueDto

**Files:**
- Modify: `src/main/java/com/daf360/payroll/modules/parameterset/dto/PayrollRubriqueDto.java`

- [ ] **Step 1: Read the current record**

Open `PayrollRubriqueDto.java`. The current components match what `toDto()` passes in `ParameterSetService`:
`id, code, labelFr, labelEn, nature, calcMode, amount, rate, employerSharePct, employeeSharePct, isSubjectToSocialCharges, isSubjectToIrpp, direction, contractTypes, isActive, createdAt`

- [ ] **Step 2: Add capAmount after rate**

Replace the file content with:

```java
package com.daf360.payroll.modules.parameterset.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PayrollRubriqueDto(
    Long id,
    String code,
    String labelFr,
    String labelEn,
    String nature,
    String calcMode,
    BigDecimal amount,
    BigDecimal rate,
    BigDecimal capAmount,
    BigDecimal employerSharePct,
    BigDecimal employeeSharePct,
    Boolean isSubjectToSocialCharges,
    Boolean isSubjectToIrpp,
    String direction,
    String contractTypes,
    Boolean isActive,
    OffsetDateTime createdAt
) {}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/parameterset/dto/PayrollRubriqueDto.java
git commit -m "feat(parameterset): add capAmount to PayrollRubriqueDto"
```

---

### Task 7: Wire capAmount through ParameterSetService

**Files:**
- Modify: `src/main/java/com/daf360/payroll/modules/parameterset/service/ParameterSetService.java`

Two places need updating: `saveRubriques()` (maps DTO → entity) and `toDto()` (maps entity → DTO).

- [ ] **Step 1: Update saveRubriques() — add capAmount mapping**

In `saveRubriques()` (around line 211–232), the current mapping block builds a `PayrollRubrique` entity from `SavePayrollRubriqueRequest`. After `r.setRate(d.rate());`, add:

```java
r.setCapAmount(d.capAmount());
```

The full method should look like:

```java
private void saveRubriques(Long psId, List<SavePayrollRubriqueRequest> dtos) {
    rubriqueRepo.deleteByParameterSetId(psId);
    List<PayrollRubrique> entities = dtos.stream().map(d -> {
        PayrollRubrique r = new PayrollRubrique();
        r.setParameterSetId(psId);
        r.setCode(d.code());
        r.setLabelFr(d.labelFr());
        r.setLabelEn(d.labelEn());
        r.setNature(d.nature());
        r.setCalcMode(d.calcMode());
        r.setAmount(d.amount());
        r.setRate(d.rate());
        r.setCapAmount(d.capAmount());
        r.setEmployerSharePct(d.employerSharePct() != null ? d.employerSharePct() : BigDecimal.ZERO);
        r.setEmployeeSharePct(d.employeeSharePct() != null ? d.employeeSharePct() : BigDecimal.ZERO);
        r.setIsSubjectToSocialCharges(d.isSubjectToSocialCharges() != null ? d.isSubjectToSocialCharges() : false);
        r.setIsSubjectToIrpp(d.isSubjectToIrpp() != null ? d.isSubjectToIrpp() : true);
        r.setDirection(d.direction() != null ? d.direction() : "CREDIT");
        r.setContractTypes(d.contractTypes());
        r.setIsActive(d.isActive() != null ? d.isActive() : true);
        return r;
    }).toList();
    rubriqueRepo.saveAll(entities);
}
```

- [ ] **Step 2: Update toDto() — add capAmount to PayrollRubriqueDto constructor call**

In `toDto()` (around line 262–267), the rubrique stream mapping currently is:

```java
List<PayrollRubriqueDto> rubriques = rubriqueRepo.findByParameterSetId(ps.getId()).stream()
        .map(r -> new PayrollRubriqueDto(r.getId(), r.getCode(),
                r.getLabelFr(), r.getLabelEn(), r.getNature(), r.getCalcMode(),
                r.getAmount(), r.getRate(), r.getEmployerSharePct(), r.getEmployeeSharePct(),
                r.getIsSubjectToSocialCharges(), r.getIsSubjectToIrpp(),
                r.getDirection(), r.getContractTypes(), r.getIsActive(), r.getCreatedAt()))
        .toList();
```

Replace it with (`r.getCapAmount()` added after `r.getRate()`):

```java
List<PayrollRubriqueDto> rubriques = rubriqueRepo.findByParameterSetId(ps.getId()).stream()
        .map(r -> new PayrollRubriqueDto(r.getId(), r.getCode(),
                r.getLabelFr(), r.getLabelEn(), r.getNature(), r.getCalcMode(),
                r.getAmount(), r.getRate(), r.getCapAmount(),
                r.getEmployerSharePct(), r.getEmployeeSharePct(),
                r.getIsSubjectToSocialCharges(), r.getIsSubjectToIrpp(),
                r.getDirection(), r.getContractTypes(), r.getIsActive(), r.getCreatedAt()))
        .toList();
```

- [ ] **Step 3: Final compile check**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS with zero errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/parameterset/service/ParameterSetService.java
git commit -m "feat(parameterset): wire capAmount through ParameterSetService save and toDto mapping"
```

---

### Task 8: Full build and smoke test

**Files:** none (verification only)

- [ ] **Step 1: Full build**

```bash
./mvnw clean package -DskipTests -q
```

Expected: BUILD SUCCESS. The JAR is produced in `target/`.

- [ ] **Step 2: Start the service and verify Flyway runs V16**

Start the service (however the dev does it locally — IDE run config or `java -jar target/...jar`). In the startup logs, look for:

```
Flyway: Migrating schema [dbo] to version 16 - add pourcentage plafonne
```

- [ ] **Step 3: Verify the schema via Docker sqlcmd**

```bash
docker exec timesheet-sqlserver sqlcmd \
  -S localhost -U sa -P "Timesheetdev2026!**" \
  -d DAF360_PAYROLL \
  -Q "SELECT name FROM sys.columns WHERE object_id = OBJECT_ID('dbo.payroll_rubriques_legacy') ORDER BY column_id;"
```

Expected output includes: `cap_amount` in the list.

```bash
docker exec timesheet-sqlserver sqlcmd \
  -S localhost -U sa -P "Timesheetdev2026!**" \
  -d DAF360_PAYROLL \
  -Q "SELECT name, definition FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID('dbo.payroll_rubriques_legacy') AND name = 'CK_rubrique_calc_mode';"
```

Expected: definition contains `POURCENTAGE_PLAFONNE`.

- [ ] **Step 4: Verify the simulation still works (no regression)**

Call the individual simulation endpoint with Tunisia (paysId=179) and a target net salary:

```bash
curl -s -X POST http://localhost:8893/api/payroll/simulate/individual \
  -H "Authorization: Bearer <your-token>" \
  -H "Content-Type: application/json" \
  -d '{"paysId": 179, "inputNet": 2500, "contractType": "CDI", "joursTravailes": 22}' \
  | python -m json.tool
```

Expected: JSON response with `gross`, `netTaxable`, `loadedCost`, `convergenceOk: true`, etc. No 500 error.

---

## What Phase 2 unlocks

Once this plan is complete:
- A rubrique can now be saved with `calcMode: "POURCENTAGE_PLAFONNE"` and a `capAmount` value
- The simulation engine correctly computes `min(gross, capAmount) × rate`
- This enables the admin UI (Phase 2) to present the guided form for all 4 building blocks without any Java changes

**The 4 building blocks available for the Phase 2 UI:**

| French UI label | calcMode stored in DB |
|---|---|
| Montant fixe mensuel | `FIXE_MENSUEL` |
| Montant par jour travaillé | `FIXE_JOURNALIER` |
| Pourcentage du salaire brut | `POURCENTAGE_BRUT` |
| Pourcentage plafonné | `POURCENTAGE_PLAFONNE` |

The existing `POURCENTAGE_CHARGES` mode remains available but is not surfaced in the Phase 2 UI (it is an internal mode used for derived social charge calculations, not directly edited by Finance users).
