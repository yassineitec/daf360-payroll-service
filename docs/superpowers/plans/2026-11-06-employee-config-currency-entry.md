# Employee Config Currency-Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let staff enter an employee's gross salary in any currency `payroll_fx_rates` has a cached rate for, on both `daf360-payroll-frontend`'s employee-config screen and `daf360-rh-frontend`'s Rémunération tab, converting to the country's working currency before it reaches `PayrollSimulatorService` or gets persisted.

**Architecture:** Reuses the Simulator's already-built `FxEntryConversionService`/`GET /api/payroll/ref/fx-rates` as-is. `EmployeePayrollConfigService` gains the same "resolve working currency, convert if different, persist both the converted value and the original" pattern `IndividualSimulationService` already has. Both frontends get a currency picker next to the gross-salary field, each following its own repo's existing UI convention (`<daf-select>` on payroll, a native `<select>` on RH).

**Tech Stack:** Spring Boot 4.0.5, Java 17, Spring Data JPA, SQL Server (`daf360-sql` migrations), JUnit 5 + Mockito, Angular standalone components, `@khalilrebhiitec/daf360`.

---

## Facts confirmed by reading the real code (2026-11-06) — read this before starting any task

1. **`CalculateNetRequest`/`CalculateNetResponse` already exist as their own DTOs** — the spec's uncertainty about this is resolved: `CalculateNetRequest(BigDecimal grossSalary)`, one field today. Adding `inputCurrency` to it is a one-field addition, not a new DTO.
2. **The controller's `calculateNet` method currently calls `service.calculateNet(profileUserId, req.grossSalary())` — a 2-arg service method.** Adding `inputCurrency` means both the controller call site AND `EmployeePayrollConfigControllerTest` (which mocks `service.calculateNet(10L, new BigDecimal("5000.000"))`, a 2-arg stub) need updating to the new 3-arg shape in the same task as the service signature change (Task 4) — a 1-arg addition elsewhere would silently leave this test passing against a now-unused mock signature if not updated together.
3. **No existing field tells either frontend a country's "working currency" independent of a full `pays_ref` lookup.** `daf360-payroll-frontend` already has one (`salaryCurrency()`, derived from its own loaded `paysList`) and needs no change here. `daf360-rh-frontend` has **no equivalent at all** — `EmployeeProfile` carries no currency field, and this component loads no `pays_ref` list. Rather than adding a whole new cross-service `pays_ref` lookup to a repo that doesn't have one, `EmployeePayrollConfigDto` (the shared backend DTO both frontends already consume) gains a `workingCurrency` field, resolved server-side via the same `FxSnapshotService.resolve(paysId)` the service is already being wired up to use for conversion — one authoritative value, computed once, consumed by whichever frontend needs it. `daf360-payroll-frontend` is not required to switch to using it (its own `salaryCurrency()` stays as-is, proven and unchanged); `daf360-rh-frontend` is the one that actually needs it, since it has nothing else.
4. **`FxSnapshotService.resolve(paysId)` returns `FxSnapshot(currency, eur, usd, chf)`** (a record) and is already a constructor-injectable `@Service` (`FxSnapshotService(AppProperties, PayrollCountryRepository)`), confirmed by reading it directly. `FxEntryConversionService.convert(amount, fromCurrency, toCurrency)` is confirmed fully generic — no changes to either class.
5. **`EmployeePayrollConfigServiceTest`'s `service()` factory method constructs `EmployeePayrollConfigService` directly with a fixed 6-arg constructor** — adding `FxSnapshotService`/`FxEntryConversionService` params changes this constructor's arity, so every existing test in this file needs its `service()` call site (there's only the one factory method, used by all tests) updated in the same task as the constructor change (Task 4), not as an afterthought.
6. **This service's own tests use plain JUnit assertions (`assertEquals`/`assertThrows`), not AssertJ** — match this file's existing style, don't introduce AssertJ here.
7. **`GET /api/payroll/ref/fx-rates`'s `@PreAuthorize` currently allows only `VIEW_PARAMSET`/`RUN_SIMULATION`** — confirmed by reading `RefController.java` directly. A user with only `VIEW_EMPLOYEE_CONFIG`/`MANAGE_EMPLOYEE_CONFIG` would 403 today.
8. **`FxEntryConversionException` → 422 is already wired generically** in `GlobalExceptionHandler` (`@ExceptionHandler(FxEntryConversionException.class)`) — no change needed there for this new call site.
9. **`daf360-rh-frontend`'s Rémunération tab's own existing currency picker (bonuses) uses a hardcoded `BONUS_CURRENCIES` array, not a fetched list** — but per the approved spec, the NEW gross-salary picker deliberately uses the real `getFxRates()` list instead (same reasoning as the Simulator: offering a currency with no cached rate would predictably 422 on save). This is a conscious choice, not an inconsistency to "fix" — the existing bonus picker is unrelated and untouched by this plan.
10. **`daf360-sql`'s highest migration is `V35`** (`new_model_strate4_payer_tunisia_rates.sql`, added earlier this session for an unrelated fix) as of a fresh `git pull` — this plan's migration is `V36`.

---

### Task 1: `daf360-sql` migration — currency columns on employee config + its history

**Files:**
- Create: `daf360-sql/db-payroll/migration/V36__employee_config_currency_entry.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V36__employee_config_currency_entry.sql
-- Lets gross salary on employee_payroll_config be entered in any currency
-- payroll_fx_rates has a cached rate for (same mechanism as V34's simulator currency
-- entry) -- EmployeePayrollConfigService converts to the country's working currency
-- before persisting current_gross_salary/current_net_salary, and keeps the original
-- currency/amount here for display and edit-fidelity. Both columns are also added to
-- the history table, so a past change log entry still shows what was actually typed,
-- not just the converted value.
SET NOCOUNT ON;

ALTER TABLE dbo.employee_payroll_config
    ADD input_currency        NVARCHAR(3)    NULL,
        input_amount_original DECIMAL(18,4)  NULL;
GO

ALTER TABLE dbo.employee_payroll_config_history
    ADD input_currency        NVARCHAR(3)    NULL,
        input_amount_original DECIMAL(18,4)  NULL;
GO
```

- [ ] **Step 2: Apply it locally**

```bash
cd daf360-sql
powershell -File apply.ps1 -Apply -Db payroll -Only V36__employee_config_currency_entry.sql -Yes
```
Expected: `V36__employee_config_currency_entry.sql` reported `APPLIED`. The known dangerous backlog (`V25__salary_advances.sql`, `S01__seed_tunisia_egypt.sql`, `S02__minimal_parameter_set_for_pays.sql`, `fix_V25_policy_countries.sql`) must stay untouched — `-Only` ensures that; do not run a bare `-Apply -Db payroll`.

- [ ] **Step 3: Verify directly against the DB**

```bash
docker exec -e "SQLCMDPASSWORD=$DB_PASSWORD" timesheet-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost,1433 -U sa -C -I -b -d DAF360_PAYROLL -Q "SET NOCOUNT ON; SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME IN ('employee_payroll_config','employee_payroll_config_history') AND COLUMN_NAME IN ('input_currency','input_amount_original') ORDER BY TABLE_NAME, COLUMN_NAME;"
```
Expected: 4 rows (2 columns × 2 tables).

- [ ] **Step 4: Commit**

```bash
cd daf360-sql
git add db-payroll/migration/V36__employee_config_currency_entry.sql
git commit -m "feat(payroll): add currency-entry columns to employee_payroll_config

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Backend entities — `EmployeePayrollConfig`/`EmployeePayrollConfigHistory`

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfig.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfigHistory.java`

- [ ] **Step 1: `EmployeePayrollConfig` — add the two fields**

Current end of the class:
```java
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
```

Replace with:
```java
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** ISO code the gross salary was actually typed in, or null when it was entered
     *  directly in the country's working currency (the common case — no redundant data). */
    @Column(name = "input_currency", length = 3)
    private String inputCurrency;

    /** The gross salary as typed, before conversion — null together with inputCurrency. */
    @Column(name = "input_amount_original")
    private BigDecimal inputAmountOriginal;
}
```

- [ ] **Step 2: `EmployeePayrollConfigHistory` — add the same two fields**

Current end of the class:
```java
    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}
```

Replace with:
```java
    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @Column(name = "input_currency", length = 3)
    private String inputCurrency;

    @Column(name = "input_amount_original")
    private BigDecimal inputAmountOriginal;
}
```

- [ ] **Step 3: Compile**

```bash
cd daf360-payroll-service
mvn -q -o compile -DskipTests
```
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfig.java src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfigHistory.java
git commit -m "feat(payroll): add inputCurrency/inputAmountOriginal to employee config entities

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Backend DTOs — request/response shapes gain currency fields

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollConfigDto.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollConfigHistoryDto.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/employeeconfig/dto/UpsertEmployeePayrollConfigRequest.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/employeeconfig/dto/CalculateNetRequest.java`

- [ ] **Step 1: `EmployeePayrollConfigDto` — add `inputCurrency`, `inputAmountOriginal`, `workingCurrency`**

Current:
```java
public record EmployeePayrollConfigDto(
        Long profileUserId,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        Boolean isMarried,
        Integer childrenCount,
        Long updatedBy,
        OffsetDateTime updatedAt
) {}
```

Replace with:
```java
public record EmployeePayrollConfigDto(
        Long profileUserId,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        Boolean isMarried,
        Integer childrenCount,
        Long updatedBy,
        OffsetDateTime updatedAt,
        /** ISO code the stored gross salary was originally typed in; null = it was
         *  already in the working currency. */
        String inputCurrency,
        /** The originally-typed gross salary; null together with inputCurrency. */
        BigDecimal inputAmountOriginal,
        /** The country's working currency (what currentGrossSalary/currentNetSalary are
         *  actually denominated in) — resolved server-side so a caller with no other
         *  source for it (daf360-rh-frontend) doesn't need one. Null only when paysId
         *  itself is null (never-configured employee, country not yet resolved). */
        String workingCurrency
) {}
```

- [ ] **Step 2: `EmployeePayrollConfigHistoryDto` — add `inputCurrency`, `inputAmountOriginal`**

Current:
```java
public record EmployeePayrollConfigHistoryDto(
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        String reason,
        Long changedBy,
        OffsetDateTime changedAt
) {}
```

Replace with:
```java
public record EmployeePayrollConfigHistoryDto(
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        String reason,
        Long changedBy,
        OffsetDateTime changedAt,
        String inputCurrency,
        BigDecimal inputAmountOriginal
) {}
```

- [ ] **Step 3: `UpsertEmployeePayrollConfigRequest` — add optional `inputCurrency`**

Current:
```java
public record UpsertEmployeePayrollConfigRequest(
        @NotNull Long paysId,
        @NotNull String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        Boolean isMarried,
        Integer childrenCount,
        @NotBlank String reason
) {}
```

Replace with:
```java
public record UpsertEmployeePayrollConfigRequest(
        @NotNull Long paysId,
        @NotNull String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        Boolean isMarried,
        Integer childrenCount,
        @NotBlank String reason,
        /** Null/omitted = currentGrossSalary is already in the working currency (today's
         *  behavior, zero change for existing callers). */
        String inputCurrency
) {}
```

- [ ] **Step 4: `CalculateNetRequest` — add optional `inputCurrency`**

Current:
```java
public record CalculateNetRequest(
        @NotNull @Positive BigDecimal grossSalary
) {}
```

Replace with:
```java
public record CalculateNetRequest(
        @NotNull @Positive BigDecimal grossSalary,
        String inputCurrency
) {}
```

- [ ] **Step 5: Compile**

```bash
cd daf360-payroll-service
mvn -q -o compile -DskipTests
```
Expected: **compile errors** in `EmployeePayrollConfigService.java`, `EmployeePayrollConfigController.java`, and their tests — every `new EmployeePayrollConfigDto(...)`/`new EmployeePayrollConfigHistoryDto(...)`/`new CalculateNetRequest(...)` call site now has too few arguments. This is expected and fixed in Tasks 4 and the test updates within it — **do not** patch those call sites in this task; this task only changes the DTO shapes themselves. Confirm the compile fails for this reason (missing-argument errors naming these exact classes), not some other, unrelated reason.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollConfigDto.java src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollConfigHistoryDto.java src/main/java/com/daf360/payroll/modules/employeeconfig/dto/UpsertEmployeePayrollConfigRequest.java src/main/java/com/daf360/payroll/modules/employeeconfig/dto/CalculateNetRequest.java
git commit -m "feat(payroll): add currency-entry fields to employee config DTOs

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

Note: this commit leaves the module in a non-compiling state until Task 4 lands — both are part of the same change, split only for reviewability. Do not treat Task 3's compile failure in Step 5 as a blocker; proceed straight to Task 4.

---

### Task 4: `EmployeePayrollConfigService` + controller — wire the conversion through

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollConfigService.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/employeeconfig/controller/EmployeePayrollConfigController.java`
- Modify: `daf360-payroll-service/src/test/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollConfigServiceTest.java`
- Modify: `daf360-payroll-service/src/test/java/com/daf360/payroll/modules/employeeconfig/controller/EmployeePayrollConfigControllerTest.java`

This is the core of the feature — read `IndividualSimulationService.java` fully first (already does exactly this pattern for the Simulator) if you have not already, so the conversion logic here mirrors it exactly rather than being independently re-derived.

- [ ] **Step 1: Update the two existing tests' call sites first, so you have a clean failing baseline before changing production code**

In `EmployeePayrollConfigServiceTest.java`, add the two new mocks and update the factory method:

Current:
```java
    @Mock EmployeePayrollConfigRepository repo;
    @Mock EmployeePayrollConfigHistoryRepository historyRepo;
    @Mock HrEmployeeClient hrEmployeeClient;
    @Mock ParameterSetService paramSetService;
    @Mock PayrollSimulatorService simulatorService;

    private EmployeePayrollConfigService service() {
        return new EmployeePayrollConfigService(
                repo, historyRepo, hrEmployeeClient, new ObjectMapper(), paramSetService, simulatorService);
    }
```

Replace with:
```java
    @Mock EmployeePayrollConfigRepository repo;
    @Mock EmployeePayrollConfigHistoryRepository historyRepo;
    @Mock HrEmployeeClient hrEmployeeClient;
    @Mock ParameterSetService paramSetService;
    @Mock PayrollSimulatorService simulatorService;
    @Mock com.daf360.payroll.engine.FxSnapshotService fxService;
    @Mock com.daf360.payroll.modules.ref.service.FxEntryConversionService fxEntryService;

    private EmployeePayrollConfigService service() {
        return new EmployeePayrollConfigService(
                repo, historyRepo, hrEmployeeClient, new ObjectMapper(), paramSetService, simulatorService,
                fxService, fxEntryService);
    }
```

Two of the four existing tests resolve a `paysId` of `179L` and will reach the new, now-unconditional `fxService.resolve(...)` call in production code (both `upsert()` and `calculateNet()` call it before anything else): `upsert_savesGrossSalaryOnBothEntityAndHistory` and `calculateNet_happyPath_returnsNetFromSimulatorAndFiltersBenefitsAndForwardsExactArgs`. Add this stub to each, right after their existing paysId-related stubbing, so the call has something to return instead of Mockito's default `null` (which would NPE on `.currency()`):

```java
        when(fxService.resolve(179L)).thenReturn(new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", java.math.BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));
```
(`getOrDefault_neverConfigured_returnsDefaultWithNullGrossAndNetSalary` and `calculateNet_noPaysConfigured_throwsAndNeverCallsSimulationCollaborators` never reach a resolved `paysId`, so they need no such stub — don't add one there.)

**Also fix `upsert_savesGrossSalaryOnBothEntityAndHistory`'s `UpsertEmployeePayrollConfigRequest` construction** — the record gained a 9th field in Task 3, so this existing 8-arg call site no longer compiles. Current:
```java
        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("5000.000"), new BigDecimal("3800.000"),
                false, 0, "Ajustement");
```
Replace with (trailing `null` = no currency conversion, matching this test's original intent exactly):
```java
        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("5000.000"), new BigDecimal("3800.000"),
                false, 0, "Ajustement", null);
```
(Confirmed via a full-repo grep that these are the ONLY two call sites anywhere constructing `UpsertEmployeePayrollConfigRequest`/`CalculateNetRequest` directly in Java — no other hidden usages to fix.)

**Also fix the two existing direct calls to `service().calculateNet(...)`** in this same test file — Task 4 Step 4 changes the service method's signature from 2 args to 3 (`profileUserId, grossSalary, inputCurrency`), so both of these no longer compile as written. In `calculateNet_happyPath_returnsNetFromSimulatorAndFiltersBenefitsAndForwardsExactArgs`, current:
```java
        CalculateNetResponse response = service().calculateNet(10L, new BigDecimal("6000.000"));
```
Replace with:
```java
        CalculateNetResponse response = service().calculateNet(10L, new BigDecimal("6000.000"), null);
```
In `calculateNet_noPaysConfigured_throwsAndNeverCallsSimulationCollaborators`, current:
```java
        assertThrows(IllegalArgumentException.class,
                () -> service().calculateNet(10L, new BigDecimal("5000.000")));
```
Replace with:
```java
        assertThrows(IllegalArgumentException.class,
                () -> service().calculateNet(10L, new BigDecimal("5000.000"), null));
```
Passing `null` for `inputCurrency` in both preserves each test's original intent exactly (no conversion — the same behavior the 2-arg method always had). The `grossCaptor`/`computeFromGross` verification later in `calculateNet_happyPath_...` is unaffected: with `inputCurrency` null, `convertedGross` equals `grossSalary` unchanged, so the captured value is still `6000.000` exactly as before — no change needed to that assertion.

In `EmployeePayrollConfigControllerTest.java`:

Current:
```java
    @Test
    void calculateNet_delegatesToServiceAndReturnsNetInHand() {
        when(service.calculateNet(10L, new BigDecimal("5000.000")))
                .thenReturn(new CalculateNetResponse(new BigDecimal("3820.500")));

        EmployeePayrollConfigController controller = new EmployeePayrollConfigController(service, userContext);
        CalculateNetResponse result = controller.calculateNet(10L, new CalculateNetRequest(new BigDecimal("5000.000")));

        assertEquals(new BigDecimal("3820.500"), result.netInHand());
    }
```

Replace with:
```java
    @Test
    void calculateNet_delegatesToServiceAndReturnsNetInHand() {
        when(service.calculateNet(10L, new BigDecimal("5000.000"), null))
                .thenReturn(new CalculateNetResponse(new BigDecimal("3820.500")));

        EmployeePayrollConfigController controller = new EmployeePayrollConfigController(service, userContext);
        CalculateNetResponse result = controller.calculateNet(10L, new CalculateNetRequest(new BigDecimal("5000.000"), null));

        assertEquals(new BigDecimal("3820.500"), result.netInHand());
    }
```

- [ ] **Step 2: Write the new failing tests for the conversion itself, in `EmployeePayrollConfigServiceTest.java`**

Add these two tests to the same file (same package-private fields/`service()` factory from Step 1 apply):

```java
    @Test
    void calculateNet_convertsInputCurrencyBeforeCallingSimulator() {
        EmployeePayrollConfig existing = new EmployeePayrollConfig();
        existing.setProfileUserId(10L);
        existing.setPaysId(179L);
        existing.setContractType("CDI");
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.of(existing));
        when(fxService.resolve(179L)).thenReturn(
                new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));

        ParameterSet ps = new ParameterSet();
        ps.setId(55L);
        when(paramSetService.loadActiveEntity(179L)).thenReturn(ps);
        when(paramSetService.loadRates(55L)).thenReturn(List.of());
        when(paramSetService.loadRubriques(55L)).thenReturn(List.of());
        when(paramSetService.loadBenefits(55L)).thenReturn(List.of());

        when(fxEntryService.convert(new BigDecimal("1500.000"), "TND", "EUR"))
                .thenReturn(new BigDecimal("450.00"));

        PayrollSimulatorService.PayrollResult simulatedResult = new PayrollSimulatorService.PayrollResult(
                new BigDecimal("380.00"), null, null, null, null, null, null, null,
                null, null, null, 0, true, List.of());
        when(simulatorService.computeFromGross(
                eq(new BigDecimal("450.00")), any(ParameterSet.class), anyList(), anyList(), anyList(),
                any(String.class), anyInt(), any(), any()))
                .thenReturn(simulatedResult);

        CalculateNetResponse response = service().calculateNet(10L, new BigDecimal("1500.000"), "TND");

        assertEquals(new BigDecimal("380.00"), response.netInHand());
    }

    @Test
    void upsert_convertsAndPersistsOriginalCurrencyOnBothEntityAndHistory() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(
                new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));
        when(fxEntryService.convert(new BigDecimal("1500.000"), "TND", "EUR"))
                .thenReturn(new BigDecimal("450.00"));

        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("1500.000"), new BigDecimal("1200.000"),
                false, 0, "Ajustement", "TND");

        EmployeePayrollConfigDto result = service().upsert(10L, req, 99L);

        assertEquals(new BigDecimal("450.00"), result.currentGrossSalary());
        assertEquals("TND", result.inputCurrency());
        assertEquals(new BigDecimal("1500.000"), result.inputAmountOriginal());
        assertEquals("EUR", result.workingCurrency());

        ArgumentCaptor<com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory> historyCaptor =
                ArgumentCaptor.forClass(com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory.class);
        org.mockito.Mockito.verify(historyRepo).save(historyCaptor.capture());
        assertEquals("TND", historyCaptor.getValue().getInputCurrency());
        assertEquals(new BigDecimal("1500.000"), historyCaptor.getValue().getInputAmountOriginal());
    }

    @Test
    void upsert_sameAsWorkingCurrency_storesNullInputFields() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fxService.resolve(179L)).thenReturn(
                new com.daf360.payroll.engine.FxSnapshotService.FxSnapshot("EUR", BigDecimal.ONE, new BigDecimal("1.08"), new BigDecimal("0.95")));

        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("2500.000"), new BigDecimal("1900.000"),
                false, 0, "Ajustement", null);

        EmployeePayrollConfigDto result = service().upsert(10L, req, 99L);

        assertEquals(new BigDecimal("2500.000"), result.currentGrossSalary());
        assertNull(result.inputCurrency());
        assertNull(result.inputAmountOriginal());
        org.mockito.Mockito.verifyNoInteractions(fxEntryService);
    }
```

Add `import static org.mockito.ArgumentMatchers.eq;` to this test file's existing import block if not already present.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd daf360-payroll-service
mvn -q -o test -Dtest=EmployeePayrollConfigServiceTest,EmployeePayrollConfigControllerTest
```
Expected: compile errors (constructor arity, missing methods) — production code hasn't changed yet.

- [ ] **Step 4: Update `EmployeePayrollConfigService.java`**

Current constructor and field block:
```java
    private final EmployeePayrollConfigRepository repo;
    private final EmployeePayrollConfigHistoryRepository historyRepo;
    private final HrEmployeeClient hrEmployeeClient;
    private final ObjectMapper objectMapper;
    private final ParameterSetService paramSetService;
    private final PayrollSimulatorService simulatorService;

    public EmployeePayrollConfigService(EmployeePayrollConfigRepository repo,
                                         EmployeePayrollConfigHistoryRepository historyRepo,
                                         HrEmployeeClient hrEmployeeClient,
                                         ObjectMapper objectMapper,
                                         ParameterSetService paramSetService,
                                         PayrollSimulatorService simulatorService) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.hrEmployeeClient = hrEmployeeClient;
        this.objectMapper = objectMapper;
        this.paramSetService = paramSetService;
        this.simulatorService = simulatorService;
    }
```

Replace with:
```java
    private final EmployeePayrollConfigRepository repo;
    private final EmployeePayrollConfigHistoryRepository historyRepo;
    private final HrEmployeeClient hrEmployeeClient;
    private final ObjectMapper objectMapper;
    private final ParameterSetService paramSetService;
    private final PayrollSimulatorService simulatorService;
    private final com.daf360.payroll.engine.FxSnapshotService fxService;
    private final com.daf360.payroll.modules.ref.service.FxEntryConversionService fxEntryService;

    public EmployeePayrollConfigService(EmployeePayrollConfigRepository repo,
                                         EmployeePayrollConfigHistoryRepository historyRepo,
                                         HrEmployeeClient hrEmployeeClient,
                                         ObjectMapper objectMapper,
                                         ParameterSetService paramSetService,
                                         PayrollSimulatorService simulatorService,
                                         com.daf360.payroll.engine.FxSnapshotService fxService,
                                         com.daf360.payroll.modules.ref.service.FxEntryConversionService fxEntryService) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.hrEmployeeClient = hrEmployeeClient;
        this.objectMapper = objectMapper;
        this.paramSetService = paramSetService;
        this.simulatorService = simulatorService;
        this.fxService = fxService;
        this.fxEntryService = fxEntryService;
    }
```

Current `calculateNet`:
```java
    public CalculateNetResponse calculateNet(Long profileUserId, BigDecimal grossSalary) {
        EmployeePayrollConfigDto cfg = getOrDefault(profileUserId);
        if (cfg.paysId() == null) {
            throw new IllegalArgumentException(
                    "Impossible de calculer le net : aucun pays configuré pour cet employé.");
        }
        ParameterSet ps = paramSetService.loadActiveEntity(cfg.paysId());
        List<SocialChargeRate> rates = paramSetService.loadRates(ps.getId());
        List<BenefitCatalogue> allBenefits = paramSetService.loadBenefits(ps.getId());
        List<PayrollRubrique> rubriques = paramSetService.loadRubriques(ps.getId());
        List<BenefitCatalogue> benefits = allBenefits.stream()
                .filter(b -> cfg.selectedBenefitCodes().contains(b.getBenefitCode()))
                .toList();

        // 22 mirrors IndividualSimulationService's own joursTravailes default; this screen
        // doesn't expose an override.
        PayrollSimulatorService.PayrollResult result = simulatorService.computeFromGross(
                grossSalary, ps, rates, benefits, rubriques, cfg.contractType(), 22,
                cfg.isMarried(), cfg.childrenCount());

        return new CalculateNetResponse(result.netInHand());
    }
```

Replace with:
```java
    public CalculateNetResponse calculateNet(Long profileUserId, BigDecimal grossSalary, String inputCurrency) {
        EmployeePayrollConfigDto cfg = getOrDefault(profileUserId);
        if (cfg.paysId() == null) {
            throw new IllegalArgumentException(
                    "Impossible de calculer le net : aucun pays configuré pour cet employé.");
        }
        ParameterSet ps = paramSetService.loadActiveEntity(cfg.paysId());
        List<SocialChargeRate> rates = paramSetService.loadRates(ps.getId());
        List<BenefitCatalogue> allBenefits = paramSetService.loadBenefits(ps.getId());
        List<PayrollRubrique> rubriques = paramSetService.loadRubriques(ps.getId());
        List<BenefitCatalogue> benefits = allBenefits.stream()
                .filter(b -> cfg.selectedBenefitCodes().contains(b.getBenefitCode()))
                .toList();

        // workingCurrency comes straight off cfg — getOrDefault() already resolved it via
        // fxService, no need to call it a second time here.
        BigDecimal convertedGross = inputCurrency == null
                ? grossSalary
                : fxEntryService.convert(grossSalary, inputCurrency, cfg.workingCurrency());

        // 22 mirrors IndividualSimulationService's own joursTravailes default; this screen
        // doesn't expose an override.
        PayrollSimulatorService.PayrollResult result = simulatorService.computeFromGross(
                convertedGross, ps, rates, benefits, rubriques, cfg.contractType(), 22,
                cfg.isMarried(), cfg.childrenCount());

        return new CalculateNetResponse(result.netInHand());
    }
```

Current `upsert`:
```java
    public EmployeePayrollConfigDto upsert(Long profileUserId, UpsertEmployeePayrollConfigRequest req, Long actingUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        String benefitCodesJson = toJson(req.selectedBenefitCodes());

        EmployeePayrollConfig entity = repo.findByProfileUserId(profileUserId).orElseGet(() -> {
            EmployeePayrollConfig created = new EmployeePayrollConfig();
            created.setProfileUserId(profileUserId);
            created.setCreatedAt(now);
            return created;
        });
        entity.setPaysId(req.paysId());
        entity.setContractType(req.contractType());
        entity.setSelectedBenefitCodes(benefitCodesJson);
        entity.setCurrentGrossSalary(req.currentGrossSalary());
        entity.setCurrentNetSalary(req.currentNetSalary());
        entity.setIsMarried(req.isMarried() != null ? req.isMarried() : false);
        entity.setChildrenCount(req.childrenCount() != null ? req.childrenCount() : 0);
        entity.setUpdatedBy(actingUserId);
        entity.setUpdatedAt(now);
        entity = repo.save(entity);

        EmployeePayrollConfigHistory history = new EmployeePayrollConfigHistory();
        history.setProfileUserId(profileUserId);
        history.setPaysId(req.paysId());
        history.setContractType(req.contractType());
        history.setSelectedBenefitCodes(benefitCodesJson);
        history.setCurrentGrossSalary(req.currentGrossSalary());
        history.setCurrentNetSalary(req.currentNetSalary());
        history.setReason(req.reason());
        history.setChangedBy(actingUserId);
        history.setChangedAt(now);
        historyRepo.save(history);

        return toDto(entity);
    }
```

Replace with:
```java
    public EmployeePayrollConfigDto upsert(Long profileUserId, UpsertEmployeePayrollConfigRequest req, Long actingUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        String benefitCodesJson = toJson(req.selectedBenefitCodes());

        com.daf360.payroll.engine.FxSnapshotService.FxSnapshot fx = fxService.resolve(req.paysId());
        boolean currencyConverted = req.inputCurrency() != null
                && !req.inputCurrency().equalsIgnoreCase(fx.currency());
        BigDecimal rawGross = req.currentGrossSalary();
        BigDecimal convertedGross = (rawGross == null || !currencyConverted)
                ? rawGross
                : fxEntryService.convert(rawGross, req.inputCurrency(), fx.currency());
        String storedInputCurrency = currencyConverted ? req.inputCurrency().toUpperCase() : null;
        BigDecimal storedInputAmount = currencyConverted ? rawGross : null;

        EmployeePayrollConfig entity = repo.findByProfileUserId(profileUserId).orElseGet(() -> {
            EmployeePayrollConfig created = new EmployeePayrollConfig();
            created.setProfileUserId(profileUserId);
            created.setCreatedAt(now);
            return created;
        });
        entity.setPaysId(req.paysId());
        entity.setContractType(req.contractType());
        entity.setSelectedBenefitCodes(benefitCodesJson);
        entity.setCurrentGrossSalary(convertedGross);
        entity.setCurrentNetSalary(req.currentNetSalary());
        entity.setIsMarried(req.isMarried() != null ? req.isMarried() : false);
        entity.setChildrenCount(req.childrenCount() != null ? req.childrenCount() : 0);
        entity.setInputCurrency(storedInputCurrency);
        entity.setInputAmountOriginal(storedInputAmount);
        entity.setUpdatedBy(actingUserId);
        entity.setUpdatedAt(now);
        entity = repo.save(entity);

        EmployeePayrollConfigHistory history = new EmployeePayrollConfigHistory();
        history.setProfileUserId(profileUserId);
        history.setPaysId(req.paysId());
        history.setContractType(req.contractType());
        history.setSelectedBenefitCodes(benefitCodesJson);
        history.setCurrentGrossSalary(convertedGross);
        history.setCurrentNetSalary(req.currentNetSalary());
        history.setReason(req.reason());
        history.setChangedBy(actingUserId);
        history.setChangedAt(now);
        history.setInputCurrency(storedInputCurrency);
        history.setInputAmountOriginal(storedInputAmount);
        historyRepo.save(history);

        return toDto(entity);
    }
```

Current `toDto`/`toHistoryDto`:
```java
    private EmployeePayrollConfigDto toDto(EmployeePayrollConfig e) {
        return new EmployeePayrollConfigDto(
                e.getProfileUserId(), e.getPaysId(), e.getContractType(),
                fromJson(e.getSelectedBenefitCodes()), e.getCurrentGrossSalary(), e.getCurrentNetSalary(),
                e.getIsMarried(), e.getChildrenCount(),
                e.getUpdatedBy(), e.getUpdatedAt());
    }

    private EmployeePayrollConfigHistoryDto toHistoryDto(EmployeePayrollConfigHistory h) {
        return new EmployeePayrollConfigHistoryDto(
                h.getPaysId(), h.getContractType(), fromJson(h.getSelectedBenefitCodes()),
                h.getCurrentGrossSalary(), h.getCurrentNetSalary(), h.getReason(), h.getChangedBy(), h.getChangedAt());
    }
```

Replace with:
```java
    private EmployeePayrollConfigDto toDto(EmployeePayrollConfig e) {
        String workingCurrency = e.getPaysId() == null ? null : fxService.resolve(e.getPaysId()).currency();
        return new EmployeePayrollConfigDto(
                e.getProfileUserId(), e.getPaysId(), e.getContractType(),
                fromJson(e.getSelectedBenefitCodes()), e.getCurrentGrossSalary(), e.getCurrentNetSalary(),
                e.getIsMarried(), e.getChildrenCount(),
                e.getUpdatedBy(), e.getUpdatedAt(),
                e.getInputCurrency(), e.getInputAmountOriginal(), workingCurrency);
    }

    private EmployeePayrollConfigHistoryDto toHistoryDto(EmployeePayrollConfigHistory h) {
        return new EmployeePayrollConfigHistoryDto(
                h.getPaysId(), h.getContractType(), fromJson(h.getSelectedBenefitCodes()),
                h.getCurrentGrossSalary(), h.getCurrentNetSalary(), h.getReason(), h.getChangedBy(), h.getChangedAt(),
                h.getInputCurrency(), h.getInputAmountOriginal());
    }
```

Also find `getOrDefault`'s final `return` line (the never-configured-employee branch) — current:
```java
        return new EmployeePayrollConfigDto(profileUserId, paysId, contractType, List.of(), null, null, false, 0, null, null);
```
Replace with (a never-configured employee has no stored currency fields, and `workingCurrency` is only resolvable if `paysId` was actually found):
```java
        String workingCurrency = paysId == null ? null : fxService.resolve(paysId).currency();
        return new EmployeePayrollConfigDto(profileUserId, paysId, contractType, List.of(), null, null, false, 0, null, null, null, null, workingCurrency);
```

- [ ] **Step 5: Update `EmployeePayrollConfigController.java`'s `calculateNet` to pass the new field through**

Current:
```java
    @PostMapping("/{profileUserId}/calculate-net")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public CalculateNetResponse calculateNet(@PathVariable Long profileUserId,
                                              @Valid @RequestBody CalculateNetRequest req) {
        return service.calculateNet(profileUserId, req.grossSalary());
    }
```

Replace with:
```java
    @PostMapping("/{profileUserId}/calculate-net")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public CalculateNetResponse calculateNet(@PathVariable Long profileUserId,
                                              @Valid @RequestBody CalculateNetRequest req) {
        return service.calculateNet(profileUserId, req.grossSalary(), req.inputCurrency());
    }
```

- [ ] **Step 6: Run the tests again to verify they pass**

```bash
mvn -q -o test -Dtest=EmployeePayrollConfigServiceTest,EmployeePayrollConfigControllerTest
```
Expected: `Tests run: 7` (4 existing + 3 new in the service test) `, Failures: 0, Errors: 0` for the service test, and `Tests run: 1, Failures: 0, Errors: 0` for the controller test.

- [ ] **Step 7: Run the full module suite**

```bash
mvn -q -o test
```
Expected: all pass. This module had 128 passing tests before this plan (per the prior engine-run-fix plan's final count) — note the new total.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollConfigService.java src/main/java/com/daf360/payroll/modules/employeeconfig/controller/EmployeePayrollConfigController.java src/test/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollConfigServiceTest.java src/test/java/com/daf360/payroll/modules/employeeconfig/controller/EmployeePayrollConfigControllerTest.java
git commit -m "feat(payroll): convert employee-config gross salary from its entered currency

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Widen `/fx-rates` permission for employee-config roles

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/ref/controller/RefController.java`

- [ ] **Step 1: Widen the `@PreAuthorize`**

Current:
```java
    /** Currencies the simulator's amount field can offer, with their cached rate-to-EUR --
     *  the frontend never does its own FX math, it only needs the list of available codes. */
    @GetMapping("/fx-rates")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<FxRateDto> listFxRates() {
```

Replace with:
```java
    /** Currencies the simulator's and employee-config's amount fields can offer, with their
     *  cached rate-to-EUR -- the frontend never does its own FX math, it only needs the list
     *  of available codes. */
    @GetMapping("/fx-rates")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.VIEW_PARAMSET + "','"
        + PermissionCatalog.RUN_SIMULATION + "','"
        + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','"
        + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public List<FxRateDto> listFxRates() {
```

- [ ] **Step 2: Compile and run the full suite**

```bash
cd daf360-payroll-service
mvn -q -o compile -DskipTests
mvn -q -o test
```
Expected: both clean.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/ref/controller/RefController.java
git commit -m "feat(ref): allow employee-config roles to read fx-rates

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: `daf360-payroll-frontend` — local DTOs and service gain currency fields

**Files:**
- Modify: `daf360-payroll-frontend/src/app/modules/employee-config/employee-config.service.ts`

- [ ] **Step 1: Update the local DTOs and `calculateNet`'s signature**

Current:
```typescript
export interface EmployeePayrollConfigDto {
  profileUserId: number;
  // Null on a never-configured employee whose country the cross-service HR lookup couldn't
  // resolve — the admin picks one explicitly via the country select in that case.
  paysId: number | null;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  /** Family IRPP-base deduction inputs — see ParameterSetDto's familyDeduction* fields. */
  isMarried: boolean;
  childrenCount: number;
  updatedBy: number | null;
  updatedAt: string | null;
}

export interface UpsertEmployeePayrollConfigRequest {
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  isMarried: boolean;
  childrenCount: number;
  reason: string;
}

/** Une version enregistrée de la configuration (`GET …/{id}/history`, plus récente d'abord). */
export interface EmployeePayrollConfigHistoryDto {
  paysId: number | null;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  reason: string | null;
  changedBy: number | null;
  changedAt: string;
}

export interface CalculateNetResponse {
  netInHand: number;
}
```

Replace with:
```typescript
export interface EmployeePayrollConfigDto {
  profileUserId: number;
  // Null on a never-configured employee whose country the cross-service HR lookup couldn't
  // resolve — the admin picks one explicitly via the country select in that case.
  paysId: number | null;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  /** Family IRPP-base deduction inputs — see ParameterSetDto's familyDeduction* fields. */
  isMarried: boolean;
  childrenCount: number;
  updatedBy: number | null;
  updatedAt: string | null;
  /** Set only when the stored gross salary was originally typed in a different currency
   *  than the working one — null in the common case. */
  inputCurrency: string | null;
  inputAmountOriginal: number | null;
  /** The country's working currency — resolved server-side, same value `salaryCurrency()`
   *  already derives locally from `paysList`; present for parity with other consumers of
   *  this DTO shape, not required reading here. */
  workingCurrency: string | null;
}

export interface UpsertEmployeePayrollConfigRequest {
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  isMarried: boolean;
  childrenCount: number;
  reason: string;
  /** Omit/null when currentGrossSalary is already in the working currency. */
  inputCurrency?: string | null;
}

/** Une version enregistrée de la configuration (`GET …/{id}/history`, plus récente d'abord). */
export interface EmployeePayrollConfigHistoryDto {
  paysId: number | null;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  reason: string | null;
  changedBy: number | null;
  changedAt: string;
  inputCurrency: string | null;
  inputAmountOriginal: number | null;
}

export interface CalculateNetResponse {
  netInHand: number;
}
```

- [ ] **Step 2: Update `calculateNet()` to send `inputCurrency`**

Current:
```typescript
  calculateNet(profileUserId: number, grossSalary: number): Observable<CalculateNetResponse> {
    return this.http.post<CalculateNetResponse>(`${this.base}/${profileUserId}/calculate-net`, { grossSalary });
  }
```

Replace with:
```typescript
  calculateNet(profileUserId: number, grossSalary: number, inputCurrency?: string | null): Observable<CalculateNetResponse> {
    return this.http.post<CalculateNetResponse>(`${this.base}/${profileUserId}/calculate-net`, { grossSalary, inputCurrency });
  }
```

- [ ] **Step 3: Type-check**

```bash
cd daf360-payroll-frontend
npx tsc --noEmit -p tsconfig.json
```
Expected: errors in `employee-config.component.ts` (callers of `calculateNet`/constructors of `UpsertEmployeePayrollConfigRequest` are unaffected since the new fields are optional/additional — if any error appears here, it will be fixed in Task 7; do not fix it in this task).

- [ ] **Step 4: Commit**

```bash
git add src/app/modules/employee-config/employee-config.service.ts
git commit -m "feat(payroll): add currency-entry fields to employee-config service types

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: `daf360-payroll-frontend` — currency picker on the employee-config screen

**Files:**
- Modify: `daf360-payroll-frontend/src/app/modules/employee-config/employee-config.component.ts`
- Modify: `daf360-payroll-frontend/src/app/modules/employee-config/employee-config.component.html`
- Modify: `daf360-payroll-frontend/public/i18n/fr.json`
- Modify: `daf360-payroll-frontend/public/i18n/en.json`

This replicates the Simulator's own currency picker (`simulator.component.ts`'s `fxRates`/`currencyOptions`/`setCurrency`, already built this session) — read that file's equivalent section first if you have not already, so this isn't independently re-derived.

- [ ] **Step 1: Add `fxRates`/`currencyOptions`/currency state to `employee-config.component.ts`**

Add the import for `FxRateDto` to the existing `PayrollApiService` import line. Current:
```typescript
import { PayrollApiService, BenefitCatalogueDto, PaysDto } from '../../core/payroll-api.service';
```
Replace with:
```typescript
import { PayrollApiService, BenefitCatalogueDto, PaysDto, FxRateDto } from '../../core/payroll-api.service';
```

Add this block right after the existing `salaryCurrency` computed (so it sits with the other salary/currency-related state):
```typescript
  /** `GET /api/payroll/ref/fx-rates` — currencies the gross-salary field's picker can
   *  offer, besides the country's own working currency (always offered, never needs a
   *  fetched rate) — same source the Simulator's own currency picker uses. */
  readonly fxRates = signal<FxRateDto[]>([]);

  /** The field's current currency: what's explicitly selected on the in-progress config,
   *  or the working currency when nothing has been chosen yet. */
  readonly currentCurrency = computed(() =>
    this.config()?.inputCurrency ?? this.salaryCurrency());

  /** Working currency first (always valid, even before fxRates has loaded), then every
   *  other currency payroll has a cached rate for, alphabetically — same ordering as the
   *  Simulator's own picker. */
  readonly currencyOptions = computed((): SelectOption[] => {
    const working = this.salaryCurrency();
    const others = this.fxRates()
      .map(r => r.currencyCode)
      .filter(c => c !== working)
      .sort();
    return [working, ...others]
      .filter((c): c is string => !!c)
      .map(c => ({ value: c, label: c }));
  });

  readonly currencySelection = computed((): string[] => [this.currentCurrency()].filter(Boolean) as string[]);

  setCurrency(selected: string[]): void {
    const cfg = this.config();
    if (!cfg) return;
    const picked = selected[0];
    // Picking back the working currency is treated as null, not an explicit match — same
    // "no conversion" convention the backend already uses.
    this.config.set({ ...cfg, inputCurrency: picked && picked !== this.salaryCurrency() ? picked : null });
  }
```

- [ ] **Step 2: Load `fxRates` in `ngOnInit()`**

Current:
```typescript
  ngOnInit(): void {
    this.payrollApi.listPays().subscribe(pays => this.paysList.set(pays));
    this.payrollApi.listPayrollPays().subscribe(pays => this.payrollPays.set(pays));
    if (Number.isFinite(this.userId)) this.load(this.userId);
```

Replace with:
```typescript
  ngOnInit(): void {
    this.payrollApi.listPays().subscribe(pays => this.paysList.set(pays));
    this.payrollApi.listPayrollPays().subscribe(pays => this.payrollPays.set(pays));
    this.payrollApi.getFxRates().subscribe(rates => this.fxRates.set(rates));
    if (Number.isFinite(this.userId)) this.load(this.userId);
```

- [ ] **Step 3: Send `inputCurrency` on `calculateNet()` and `upsert()`**

Current `calculateNet`:
```typescript
  calculateNet(then?: (net: number) => void, otherwise?: () => void): void {
    const cfg = this.config();
    if (cfg === null || cfg.currentGrossSalary == null || this.savedConfig()?.paysId == null) { otherwise?.(); return; }
    const gross = cfg.currentGrossSalary;
    this.calculatingNet.set(true);
    this.svc.calculateNet(this.userId, gross).subscribe({
```

Replace with:
```typescript
  calculateNet(then?: (net: number) => void, otherwise?: () => void): void {
    const cfg = this.config();
    if (cfg === null || cfg.currentGrossSalary == null || this.savedConfig()?.paysId == null) { otherwise?.(); return; }
    const gross = cfg.currentGrossSalary;
    this.calculatingNet.set(true);
    this.svc.calculateNet(this.userId, gross, cfg.inputCurrency).subscribe({
```

Current `upsert`'s request object:
```typescript
    this.svc.upsert(this.userId, {
      paysId: cfg.paysId!,
      contractType: cfg.contractType,
      selectedBenefitCodes: cfg.selectedBenefitCodes,
      currentGrossSalary: cfg.currentGrossSalary,
      currentNetSalary: cfg.currentNetSalary,
      isMarried: cfg.isMarried,
      childrenCount: cfg.childrenCount,
      reason,
    }).subscribe({
```

Replace with:
```typescript
    this.svc.upsert(this.userId, {
      paysId: cfg.paysId!,
      contractType: cfg.contractType,
      selectedBenefitCodes: cfg.selectedBenefitCodes,
      currentGrossSalary: cfg.currentGrossSalary,
      currentNetSalary: cfg.currentNetSalary,
      isMarried: cfg.isMarried,
      childrenCount: cfg.childrenCount,
      reason,
      inputCurrency: cfg.inputCurrency,
    }).subscribe({
```

- [ ] **Step 4: Add the `<daf-select>` picker to the template, next to SALAIRE BRUT**

Current:
```html
              <div class="field-row">
                <daf-form-field
                  [options]="{ label: 'PAYROLL.EMPLOYEE_CONFIG.GROSS_SALARY' | translate, type: 'number', align: 'end', suffixText: salaryCurrency(), placeholder: '0.00', fullWidth: true }"
                  [value]="cfg.currentGrossSalary"
                  (valueChange)="onGrossSalaryChange(toNumber($event))">
                </daf-form-field>
                <daf-form-field
                  [options]="{
                    label: 'PAYROLL.EMPLOYEE_CONFIG.CURRENT_SALARY' | translate, type: 'number', align: 'end',
                    suffixText: salaryCurrency(), placeholder: '—', fullWidth: true, readonly: true,
                    hint: calculatingNet() ? ('PAYROLL.EMPLOYEE_CONFIG.NET_CALCULATING' | translate) : netHint()
                  }"
                  [value]="cfg.currentNetSalary">
                </daf-form-field>
              </div>
```

Replace with:
```html
              <div class="field-row">
                <div class="flex-1 min-w-0">
                  <daf-form-field
                    [options]="{ label: 'PAYROLL.EMPLOYEE_CONFIG.GROSS_SALARY' | translate, type: 'number', align: 'end', suffixText: currentCurrency(), placeholder: '0.00', fullWidth: true }"
                    [value]="cfg.currentGrossSalary"
                    (valueChange)="onGrossSalaryChange(toNumber($event))">
                  </daf-form-field>
                  <daf-select
                    class="mt-2 block"
                    [options]="currencyOptions()"
                    [config]="{
                      label: 'PAYROLL.EMPLOYEE_CONFIG.CURRENCY_LABEL' | translate,
                      hint: 'PAYROLL.EMPLOYEE_CONFIG.CURRENCY_HINT' | translate: { working: salaryCurrency() },
                    }"
                    [selected]="currencySelection()"
                    (selectedChange)="setCurrency($event)">
                  </daf-select>
                </div>
                <daf-form-field
                  [options]="{
                    label: 'PAYROLL.EMPLOYEE_CONFIG.CURRENT_SALARY' | translate, type: 'number', align: 'end',
                    suffixText: salaryCurrency(), placeholder: '—', fullWidth: true, readonly: true,
                    hint: calculatingNet() ? ('PAYROLL.EMPLOYEE_CONFIG.NET_CALCULATING' | translate) : netHint()
                  }"
                  [value]="cfg.currentNetSalary">
                </daf-form-field>
              </div>
              @if (cfg.inputCurrency && cfg.inputAmountOriginal != null) {
                <p class="text-[12.5px] text-on-surface-variant mt-1">
                  {{ 'PAYROLL.EMPLOYEE_CONFIG.ENTERED_AS' | translate: { amount: money(cfg.inputAmountOriginal, cfg.inputCurrency) } }}
                </p>
              }
```

Note: the gross field's own `suffixText` changes from `salaryCurrency()` to `currentCurrency()` (what's actually being typed in), while the net field's `suffixText` stays `salaryCurrency()` (net is always shown in the working currency — it's the server-computed result, never re-expressed in the input currency).

- [ ] **Step 5: Add the new i18n keys**

In `public/i18n/fr.json`, inside the `PAYROLL.EMPLOYEE_CONFIG` block, current:
```json
      "GROSS_SALARY": "Salaire brut",
      "CALCULATE_ERROR": "Impossible de calculer le salaire net.",
```
Replace with:
```json
      "GROSS_SALARY": "Salaire brut",
      "CURRENCY_LABEL": "Devise saisie",
      "CURRENCY_HINT": "Devise de travail du pays : {{working}}. Choisissez une autre devise si vous saisissez dans une devise différente.",
      "ENTERED_AS": "Saisi comme : {{amount}}",
      "CALCULATE_ERROR": "Impossible de calculer le salaire net.",
```

In `public/i18n/en.json`, find the equivalent `GROSS_SALARY`/`CALCULATE_ERROR` pair in the same `PAYROLL.EMPLOYEE_CONFIG` block and insert the English equivalents in the same position:
```json
      "GROSS_SALARY": "Gross salary",
      "CURRENCY_LABEL": "Entered currency",
      "CURRENCY_HINT": "The country's working currency is {{working}}. Pick a different one if you're entering in another currency.",
      "ENTERED_AS": "Entered as: {{amount}}",
      "CALCULATE_ERROR": "Unable to calculate the net salary.",
```

- [ ] **Step 6: Type-check**

```bash
cd daf360-payroll-frontend
npx tsc --noEmit -p tsconfig.json
```
Expected: no errors.

- [ ] **Step 7: Validate both i18n files are still well-formed JSON**

```bash
node -e "JSON.parse(require('fs').readFileSync('public/i18n/fr.json','utf8')); JSON.parse(require('fs').readFileSync('public/i18n/en.json','utf8')); console.log('JSON OK')"
```
Expected: `JSON OK`.

- [ ] **Step 8: Commit**

```bash
git add src/app/modules/employee-config/employee-config.component.ts src/app/modules/employee-config/employee-config.component.html public/i18n/fr.json public/i18n/en.json
git commit -m "feat(payroll): add currency picker to the employee-config gross-salary field

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: `daf360-rh-frontend` — `RemunerationService` gains currency fields + `getFxRates()`

**Files:**
- Modify: `daf360-rh-frontend/src/app/modules/profiles/services/remuneration.service.ts`

- [ ] **Step 1: Update the local DTOs**

Current:
```typescript
export interface EmployeePayrollConfigDto {
  profileUserId: number;
  // Null on a never-configured employee whose paysId payroll-service's own cross-service HR
  // lookup couldn't resolve — see RemunerationSectionComponent's own fallback to profile().paysId.
  paysId: number | null;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  updatedBy: number | null;
  updatedAt: string | null;
}

export interface UpsertEmployeePayrollConfigRequest {
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  reason: string;
}
```

Replace with:
```typescript
export interface EmployeePayrollConfigDto {
  profileUserId: number;
  // Null on a never-configured employee whose paysId payroll-service's own cross-service HR
  // lookup couldn't resolve — see RemunerationSectionComponent's own fallback to profile().paysId.
  paysId: number | null;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  updatedBy: number | null;
  updatedAt: string | null;
  /** Set only when the stored gross salary was originally typed in a different currency
   *  than the working one — null in the common case. */
  inputCurrency: string | null;
  inputAmountOriginal: number | null;
  /** The country's working currency, resolved server-side — this app has no `pays_ref`
   *  lookup of its own, so this is the only source for it on this screen. */
  workingCurrency: string | null;
}

export interface UpsertEmployeePayrollConfigRequest {
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentGrossSalary: number | null;
  currentNetSalary: number | null;
  reason: string;
  inputCurrency?: string | null;
}

export interface FxRateDto {
  currencyCode: string;
  rateToEur: number;
}
```

- [ ] **Step 2: Update `calculateNet()` and add `getFxRates()`**

Current:
```typescript
  calculateNet(profileUserId: number, grossSalary: number): Observable<CalculateNetResponse> {
    return this.http.post<CalculateNetResponse>(`${this.base}/${profileUserId}/calculate-net`, { grossSalary });
  }
```

Replace with:
```typescript
  calculateNet(profileUserId: number, grossSalary: number, inputCurrency?: string | null): Observable<CalculateNetResponse> {
    return this.http.post<CalculateNetResponse>(`${this.base}/${profileUserId}/calculate-net`, { grossSalary, inputCurrency });
  }
```

Add this method right after `getBenefits()` (same cross-service-call pattern, new base path):
```typescript
  /** Currencies the gross-salary field's picker can offer, with their cached rate-to-EUR —
   *  same source daf360-payroll-frontend's employee-config screen and Simulator already use.
   *  Requires PAYROLL_VIEW_EMPLOYEE_CONFIG/MANAGE_EMPLOYEE_CONFIG on top of the existing
   *  employee-config permissions (already widened for this on the backend). */
  getFxRates(): Observable<FxRateDto[]> {
    return this.http.get<FxRateDto[]>(`${environment.payrollApiUrl}/api/payroll/ref/fx-rates`);
  }
```

- [ ] **Step 3: Type-check**

```bash
cd daf360-rh-frontend
npx tsc --noEmit -p tsconfig.json
```
Expected: no new errors from this file (the component using it is updated in Task 9).

- [ ] **Step 4: Commit**

```bash
git add src/app/modules/profiles/services/remuneration.service.ts
git commit -m "feat(profiles): add fx-rates and currency-entry fields to RemunerationService

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: `daf360-rh-frontend` — currency picker on the Rémunération tab

**Files:**
- Modify: `daf360-rh-frontend/src/app/modules/profiles/detail-sections/remuneration-section.component.ts`
- Modify: `daf360-rh-frontend/public/assets/i18n/fr.json`
- Modify: `daf360-rh-frontend/public/assets/i18n/en.json`

This follows this component's own existing convention: a plain native `<select>` + `[ngModel]`, exactly like the already-present bonus-currency picker in the same file — not `<daf-select>`, which this component does not import or use anywhere.

- [ ] **Step 1: Add currency state**

Add this signal and computed right after the existing `readonly error`/`readonly success` signals:
```typescript
  readonly fxRates = signal<FxRateDto[]>([]);

  /** Working currency first (once known from the loaded config), then every other
   *  currency payroll has a cached rate for, alphabetically. */
  readonly currencyOptions = computed((): string[] => {
    const working = this.config()?.workingCurrency ?? null;
    const others = this.fxRates()
      .map(r => r.currencyCode)
      .filter(c => c !== working)
      .sort();
    return [working, ...others].filter((c): c is string => !!c);
  });
```

Update the import line to pull in the new `FxRateDto` type:
Current:
```typescript
import {
  BenefitCatalogueDto, EmployeePayrollBonusDto, EmployeePayrollConfigDto, RemunerationService,
} from '../services/remuneration.service';
```
Replace with:
```typescript
import {
  BenefitCatalogueDto, EmployeePayrollBonusDto, EmployeePayrollConfigDto, FxRateDto, RemunerationService,
} from '../services/remuneration.service';
```

- [ ] **Step 2: Load `fxRates` in `ngOnInit()`**

Current end of `ngOnInit()`:
```typescript
    this.svc.getBonuses(this.profile().userId).subscribe({
      next: list => this.bonuses.set(list),
      error: () => this.bonusError.set(this.translate.instant('PROFILES.FIELDS.REMUNERATION_BONUS_LOAD_ERROR')),
    });
  }
```
Replace with:
```typescript
    this.svc.getBonuses(this.profile().userId).subscribe({
      next: list => this.bonuses.set(list),
      error: () => this.bonusError.set(this.translate.instant('PROFILES.FIELDS.REMUNERATION_BONUS_LOAD_ERROR')),
    });
    this.svc.getFxRates().subscribe({
      next: rates => this.fxRates.set(rates),
      error: () => { /* currency picker just offers the working currency alone */ },
    });
  }
```

- [ ] **Step 3: Send `inputCurrency` on `calculateNet()` and `upsert()`**

Current `calculateNet()`:
```typescript
  calculateNet(): void {
    const cfg = this.config();
    if (!cfg || cfg.currentGrossSalary == null) return;
    this.calculatingNet.set(true);
    this.error.set(null);
    this.svc.calculateNet(this.profile().userId, cfg.currentGrossSalary).subscribe({
```
Replace with:
```typescript
  calculateNet(): void {
    const cfg = this.config();
    if (!cfg || cfg.currentGrossSalary == null) return;
    this.calculatingNet.set(true);
    this.error.set(null);
    this.svc.calculateNet(this.profile().userId, cfg.currentGrossSalary, cfg.inputCurrency).subscribe({
```

Current `save()`'s request object:
```typescript
    this.svc.upsert(this.profile().userId, {
      // Non-null by construction: ngOnInit's `raw.paysId ?? this.profile().paysId` fallback
      // (profile().paysId is itself non-nullable) always resolves this before it ever lands
      // in `config`, unlike the raw EmployeePayrollConfigDto the service can return.
      paysId: cfg.paysId!,
      contractType: cfg.contractType,
      selectedBenefitCodes: cfg.selectedBenefitCodes,
      currentGrossSalary: cfg.currentGrossSalary,
      currentNetSalary: cfg.currentNetSalary,
      reason: this.reason(),
    }).subscribe({
```
Replace with:
```typescript
    this.svc.upsert(this.profile().userId, {
      // Non-null by construction: ngOnInit's `raw.paysId ?? this.profile().paysId` fallback
      // (profile().paysId is itself non-nullable) always resolves this before it ever lands
      // in `config`, unlike the raw EmployeePayrollConfigDto the service can return.
      paysId: cfg.paysId!,
      contractType: cfg.contractType,
      selectedBenefitCodes: cfg.selectedBenefitCodes,
      currentGrossSalary: cfg.currentGrossSalary,
      currentNetSalary: cfg.currentNetSalary,
      reason: this.reason(),
      inputCurrency: cfg.inputCurrency,
    }).subscribe({
```

- [ ] **Step 4: Add the picker to the template, next to the gross-salary field**

Current:
```html
          <daf-form-field
            [value]="cfg.currentGrossSalary"
            [options]="{ type: 'number', label: ('PROFILES.FIELDS.REMUNERATION_GROSS_SALARY' | translate), fullWidth: true }"
            (valueChange)="patchConfig({ currentGrossSalary: asNumber($event) })" />
```
Replace with:
```html
          <div class="flex flex-col gap-1.5">
            <daf-form-field
              [value]="cfg.currentGrossSalary"
              [options]="{ type: 'number', label: ('PROFILES.FIELDS.REMUNERATION_GROSS_SALARY' | translate), fullWidth: true }"
              (valueChange)="patchConfig({ currentGrossSalary: asNumber($event) })" />
            <div class="flex flex-col gap-1">
              <label class="text-[10px] font-semibold uppercase tracking-wider text-on-surface-variant">
                {{ 'PROFILES.FIELDS.REMUNERATION_CURRENCY_LABEL' | translate }}
              </label>
              <select class="rounded-lg border border-outline-variant px-2 py-1.5 text-[13px]"
                [ngModel]="cfg.inputCurrency ?? cfg.workingCurrency"
                (ngModelChange)="patchConfig({ inputCurrency: $event !== cfg.workingCurrency ? $event : null })">
                @for (c of currencyOptions(); track c) {
                  <option [value]="c">{{ c }}</option>
                }
              </select>
            </div>
            @if (cfg.inputCurrency && cfg.inputAmountOriginal != null) {
              <span class="text-[12.5px] text-on-surface-variant">
                {{ 'PROFILES.FIELDS.REMUNERATION_ENTERED_AS' | translate: { amount: cfg.inputAmountOriginal + ' ' + cfg.inputCurrency } }}
              </span>
            }
          </div>
```

- [ ] **Step 5: Add the new i18n keys**

In `public/assets/i18n/fr.json`, current:
```json
      "REMUNERATION_GROSS_SALARY": "Salaire brut",
      "REMUNERATION_CALCULATE_NET": "Calculer le net",
```
Replace with:
```json
      "REMUNERATION_GROSS_SALARY": "Salaire brut",
      "REMUNERATION_CURRENCY_LABEL": "Devise saisie",
      "REMUNERATION_ENTERED_AS": "Saisi comme : {{amount}}",
      "REMUNERATION_CALCULATE_NET": "Calculer le net",
```

In `public/assets/i18n/en.json`, find the equivalent `REMUNERATION_GROSS_SALARY`/`REMUNERATION_CALCULATE_NET` pair and insert the English equivalents in the same position:
```json
      "REMUNERATION_GROSS_SALARY": "Gross salary",
      "REMUNERATION_CURRENCY_LABEL": "Entered currency",
      "REMUNERATION_ENTERED_AS": "Entered as: {{amount}}",
      "REMUNERATION_CALCULATE_NET": "Calculate net",
```

- [ ] **Step 6: Type-check**

```bash
cd daf360-rh-frontend
npx tsc --noEmit -p tsconfig.json
```
Expected: no errors.

- [ ] **Step 7: Validate both i18n files are still well-formed JSON**

```bash
node -e "JSON.parse(require('fs').readFileSync('public/assets/i18n/fr.json','utf8')); JSON.parse(require('fs').readFileSync('public/assets/i18n/en.json','utf8')); console.log('JSON OK')"
```
Expected: `JSON OK`.

- [ ] **Step 8: Commit**

```bash
git add src/app/modules/profiles/detail-sections/remuneration-section.component.ts public/assets/i18n/fr.json public/assets/i18n/en.json
git commit -m "feat(profiles): add currency picker to the Rémunération gross-salary field

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Manual verification (not a subagent task — do this yourself at the end)

- [ ] Rebuild and restart the local `daf360-payroll-backend-dev` container.
- [ ] Rebuild and restart `daf360-payroll-frontend-dev` and `daf360-rh-frontend`'s dev container (whichever is running locally) so both pick up the new picker.
- [ ] On the payroll app's employee-config screen for a real Tunisia employee, pick TND, enter a gross amount, confirm "Calculer" produces a sane net and the saved record round-trips correctly on reload (currency picker shows TND again, same typed amount).
- [ ] Open the **same employee's** Rémunération tab in the RH app and confirm it shows the same config consistently (both screens read/write the exact same backend row) — pick a currency there too and confirm it saves correctly.
- [ ] Report back to the user with the actual numbers/behavior observed.

---

## Self-review (spec coverage, placeholders, type consistency)

**Spec coverage**: data model changes → Task 1. Backend entity/DTO/service/permission changes → Tasks 2–5. Payroll-frontend picker → Tasks 6–7. RH-frontend picker → Tasks 8–9. Manual round-trip testing across both screens → Task 10. The spec's "exact DTO name/shape to confirm" uncertainty for calculate-net is resolved in the Facts section (Task 4 uses the real, already-existing `CalculateNetRequest`/`CalculateNetResponse`).

**Placeholder scan**: none found — every task has real file paths (verified to exist by reading them), real current-code "before" blocks (copied from the actual files read during planning), real new code, real commands.

**Type consistency**: `inputCurrency` is `String`/`string` end to end (entity → DTO → frontend interface, all repos) matching the Simulator's own established convention exactly. `workingCurrency` — a field this plan adds that the spec didn't name explicitly — is consistently `String`/`string | null` across the backend DTO and both frontends' local type mirrors.

**Deviation from the spec's literal wording, resolved during planning**: the spec didn't anticipate that `daf360-rh-frontend` has no existing source for a country's "working currency" at all (no `pays_ref` list loaded anywhere in this component, unlike `daf360-payroll-frontend`'s own `salaryCurrency()`). Resolved by adding a `workingCurrency` field to the shared `EmployeePayrollConfigDto` (backend), resolved server-side via the same `FxSnapshotService` the conversion logic already needs — one authoritative value, no new cross-service `pays_ref` lookup added to a repo that doesn't have one. `daf360-payroll-frontend` is unaffected (keeps using its own proven `salaryCurrency()`); `daf360-rh-frontend` is the one that actually consumes the new field, since it has nothing else.
