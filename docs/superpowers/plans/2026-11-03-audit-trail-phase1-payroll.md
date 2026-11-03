# Generic Audit Trail — Phase 1: Payroll Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Roll the generic audit-trigger mechanism out to every in-scope Payroll table, wire actor capture into `daf360-payroll-service`, revive the orphaned `payroll-audit-admin` screen, and merge `audit_trail` rows into the existing "Journal des modifications" alongside the two sources it already shows.

**Architecture:** Regenerate `db-payroll/migration/V32__audit_triggers_full.sql` via `tools/generate-audit-triggers.ps1` covering all of `DAF360_PAYROLL` except the one table with full existing field-level history coverage. Wrap `daf360-payroll-service`'s autoconfigured `DataSource` with `SessionContextDataSource` so every write carries the real username into `SESSION_CONTEXT`. Add a third, `GENERIC`-sourced branch to `PayrollAdminService.audit()` backed by a new `AuditTrail` JPA entity reading `dbo.audit_trail`. Re-enable the payroll-frontend's already-built (never-removed) `app-payroll-audit-admin` screen and teach it to render the new source.

**Tech Stack:** SQL Server triggers (via the Phase 0 generator), Spring Boot/JPA (`daf360-payroll-service`), Angular standalone components (`daf360-payroll-frontend`), JUnit 5 + Mockito.

**Depends on:** Phase 0 (`docs/superpowers/plans/2026-11-03-audit-trail-phase0-mechanism.md` in `daf360-sql`) must be complete — `tools/generate-audit-triggers.ps1` and `dbo.audit_trail` (via `db-payroll/migration/V30__audit_trail.sql`) must exist before Task 1 below can run. Task 1 checks this explicitly and stops if it's missing.

**Verified against the live repos on 2026-11-03** (re-check before trusting if this plan is old): Phase 0 has been **planned but not yet executed** — `daf360-sql/tools/generate-audit-triggers.ps1` and `db-payroll/migration/V30__audit_trail.sql`/`V31__audit_triggers.sql` do not exist on disk yet. `daf360-payroll-service`'s own `src/main/resources/db/migration/` only contains `V25__tunisia_parameter_set_2026.sql` and `V26__payroll_countries_fx_rates.sql` (V1–V24 were removed from the repo in an earlier commit despite being applied live — see `project_backend_preexisting_test_failures_oct2026`/`project_payroll_app_audit` memory); this is unrelated to this plan and not fixed by it.

---

### Task 1: Confirm Phase 0 is actually done, in `daf360-sql`

**Files:** none (verification only).

- [ ] **Step 1: Check the prerequisites exist**

```powershell
cd C:\Users\ITEC2\OneDrive\Documents\projects\daf360-sql
Test-Path tools\generate-audit-triggers.ps1
Test-Path db-payroll\migration\V30__audit_trail.sql
.\apply.ps1 -Status -Db payroll
```
Expected: both `Test-Path` calls return `True`, and `-Status` shows `V30__audit_trail.sql` as `APPLIED` (not `PENDING`). **If either file is missing, or V30 is not APPLIED, stop here and complete Phase 0 first** — do not try to recreate its deliverables inline in this plan, since Phase 0's own plan already specifies their exact content and this one should not duplicate/diverge from it.

---

### Task 2: Full trigger rollout for `DAF360_PAYROLL`

**Files:**
- Create: `daf360-sql/db-payroll/migration/V32__audit_triggers_full.sql`

**Exclusion decided:** only `employee_payroll_config` is excluded — it is the one Payroll table with full field-level before/after coverage from an existing mechanism (`EmployeePayrollConfigHistory`/`EmployeePayrollConfigHistoryRepository`, written on every `EmployeePayrollConfigService.updateConfig()` call). The other two existing history tables do **not** give full-row coverage, so their source tables stay in scope:
- `salary_advance_history` only records `from_status`/`to_status`/`notes` transitions — `salary_advance`, `salary_advance_installment`, and `salary_advance_policy` (confirmed real tables via `@Table` on `SalaryAdvance`/`SalaryAdvanceInstallment`/`SalaryAdvancePolicy`) are NOT excluded.
- `payroll_precision_kpi_history` is a KPI snapshot log, not a change-audit trail for any specific source table — nothing is excluded because of it.
- `employee_payroll_bonus` (a real, separate table under the same module as `employee_payroll_config`, confirmed via its own `@Table(name = "employee_payroll_bonus")`) has **no** dedicated history coverage of its own — it stays in scope, it is not covered by `employee_payroll_config_history`.

- [ ] **Step 1: Preview the generator's output**

```powershell
cd C:\Users\ITEC2\OneDrive\Documents\projects\daf360-sql
.\tools\generate-audit-triggers.ps1 -Db payroll -OutFile db-payroll\migration\V32__audit_triggers_full.sql -Exclude employee_payroll_config -WhatIf
```
Expected: `Tables in scope: <N>` (around 27, i.e. all tables minus `employee_payroll_config` and whatever `audit_trail`/`script_ledger` self-exclusion already removes). If `SKIPPED (no primary key found...)` lists any table, stop and investigate that table's schema before continuing — do not silently proceed past an unhandled table.

- [ ] **Step 2: Generate it for real**

```powershell
.\tools\generate-audit-triggers.ps1 -Db payroll -OutFile db-payroll\migration\V32__audit_triggers_full.sql -Exclude employee_payroll_config
```
Expected: `Wrote db-payroll\migration\V32__audit_triggers_full.sql (<N> triggers).`

- [ ] **Step 3: Apply it**

```powershell
.\apply.ps1 -Apply -Db payroll -Yes
```
Expected: `APPLIED   payroll      V32__audit_triggers_full.sql`.

- [ ] **Step 4: Spot-check two tables through real application code, not raw SQL**

Pick `payroll_countries` (already proven in Phase 0) and one new one, `salary_advance`. Through the running `daf360-payroll-service` (or a direct insert if the app isn't running locally), create/update/delete a `salary_advance` row and confirm:
```sql
SELECT action, table_name, pk_json, changed_by FROM dbo.audit_trail
WHERE table_name = 'salary_advance' ORDER BY id DESC;
```
Expected: rows appear for each write. `changed_by` will be `NULL` until Task 3 wires actor capture — that is expected at this point, not a bug.

- [ ] **Step 5: Commit**

```bash
git add db-payroll/migration/V32__audit_triggers_full.sql
git commit -m "feat(payroll): roll out generic audit triggers to all in-scope tables

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Wire actor capture into `daf360-payroll-service`

**Why this works this way:** the service's `DataSource` is Spring Boot **autoconfigured** from `application.yml` (`spring.datasource.*`/`spring.datasource.hikari.*`, confirmed — there is no explicit `@Bean DataSource` anywhere in the codebase today). So the wrapper is added as a new `@Primary` bean that takes the autoconfigured one as a constructor parameter and wraps it, rather than replacing an existing bean definition.

**Files:**
- Create: `src/main/java/com/daf360/payroll/config/SessionContextDataSource.java`
- Create: `src/main/java/com/daf360/payroll/config/AuditDataSourceConfig.java`
- Test: `src/test/java/com/daf360/payroll/config/SessionContextDataSourceTest.java`

- [ ] **Step 1: Copy the reference class from `daf360-sql`**

```java
package com.daf360.payroll.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps the real DataSource so every borrowed connection has SESSION_CONTEXT('app_user')
 * set before any statement runs on it -- read by the generic audit triggers
 * (tools/generate-audit-triggers.ps1 in daf360-sql). Writes made outside the app
 * (ad-hoc SQL scripts) simply leave this NULL.
 */
public class SessionContextDataSource extends DelegatingDataSource {

    public SessionContextDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withSessionContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withSessionContext(super.getConnection(username, password));
    }

    private Connection withSessionContext(Connection connection) throws SQLException {
        try (CallableStatement cs = connection.prepareCall("{call sys.sp_set_session_context(?, ?)}")) {
            cs.setString(1, "app_user");
            cs.setString(2, currentUsername());
            cs.execute();
        }
        return connection;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
    }
}
```

- [ ] **Step 2: Write the wiring config**

```java
package com.daf360.payroll.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Makes the SessionContextDataSource wrapper the DataSource JPA/JDBC actually use, on top
 * of Spring Boot's autoconfigured Hikari pool (spring.datasource.* in application.yml) --
 * no existing bean definition is replaced, this only adds a @Primary wrapper around it.
 */
@Configuration
public class AuditDataSourceConfig {

    @Bean
    @Primary
    @ConditionalOnMissingBean(SessionContextDataSource.class)
    public DataSource auditedDataSource(DataSource dataSource) {
        return new SessionContextDataSource(dataSource);
    }
}
```

- [ ] **Step 3: Write a unit test for the wrapper**

Mirrors the Mockito style already used in `PayrollAdminServiceTest.java`.

```java
package com.daf360.payroll.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionContextDataSourceTest {

    @Mock DataSource target;
    @Mock Connection connection;
    @Mock CallableStatement callableStatement;

    @Test
    void getConnection_setsSessionContextToTheAuthenticatedUsername() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null));
        when(target.getConnection()).thenReturn(connection);
        when(connection.prepareCall("{call sys.sp_set_session_context(?, ?)}")).thenReturn(callableStatement);

        SessionContextDataSource wrapped = new SessionContextDataSource(target);
        Connection result = wrapped.getConnection();

        verify(callableStatement).setString(1, "app_user");
        verify(callableStatement).setString(2, "alice");
        verify(callableStatement).execute();
        assertSame(connection, result);

        SecurityContextHolder.clearContext();
    }

    @Test
    void getConnection_noAuthentication_setsNullUsername() throws Exception {
        SecurityContextHolder.clearContext();
        when(target.getConnection()).thenReturn(connection);
        when(connection.prepareCall("{call sys.sp_set_session_context(?, ?)}")).thenReturn(callableStatement);

        new SessionContextDataSource(target).getConnection();

        verify(callableStatement).setString(eq(2), eq((String) null));
    }
}
```

- [ ] **Step 4: Run the test**

```bash
cd daf360-payroll-service
mvn test -Dtest=SessionContextDataSourceTest
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Verify end-to-end against the real local database**

Start/restart the service locally, then make a real write through it (e.g. update a payroll country via `PUT /api/payroll/admin/countries/{id}` as an authenticated user), then check:
```sql
SELECT TOP 1 table_name, changed_by FROM dbo.audit_trail
WHERE table_name = 'payroll_countries' ORDER BY id DESC;
```
Expected: `changed_by` is the real logged-in username, not NULL. If it's still NULL, the bean likely isn't actually being picked up as `@Primary` by the JPA `EntityManagerFactory` — check `spring.jpa` properties don't pin a specific datasource bean name elsewhere.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/daf360/payroll/config/SessionContextDataSource.java \
        src/main/java/com/daf360/payroll/config/AuditDataSourceConfig.java \
        src/test/java/com/daf360/payroll/config/SessionContextDataSourceTest.java
git commit -m "feat(audit): wire SESSION_CONTEXT actor capture into the DataSource

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Revive the orphaned `payroll-audit-admin` screen

**What's actually needed:** far less than expected. The component (`payroll-audit-admin.component.ts`), its template wiring (`@case ('audit') { <app-payroll-audit-admin /> }` in `parameter-sets.component.html:101`), and its permission constant (`PAYROLL_AUDIT_PERMISSIONS`, `payroll-nav.ts:119-124`, matching the backend's real `PermissionCatalog` codes exactly) are all already correct and in place. The **only** thing disconnecting it is one commented-out line in the admin-home `CARDS` array.

**Files:**
- Modify: `daf360-payroll-frontend/src/app/modules/parameter-sets/parameter-sets.component.ts:127`

- [ ] **Step 1: Uncomment the card**

Change (line 127):
```typescript
  // { key: 'audit',           labelKey: 'PAYROLL.ADMIN_HOME.CARDS.AUDIT',            icon: 'history',         tone: 'secondary', permissions: PAYROLL_AUDIT_PERMISSIONS, helpKey: 'AUDIT' },
```
to:
```typescript
  { key: 'audit',           labelKey: 'PAYROLL.ADMIN_HOME.CARDS.AUDIT',            icon: 'history',         tone: 'secondary', permissions: PAYROLL_AUDIT_PERMISSIONS, helpKey: 'AUDIT' },
```
(Also update the explanatory comment above the `CARDS` array at lines ~108-112, which currently lists "« Historique et contrôle »" among the cards "hidden for now" — remove that card from the list since it's being restored.)

- [ ] **Step 2: Verify it builds**

```bash
cd daf360-payroll-frontend
npx ng build --configuration development
```
Expected: builds cleanly, no new errors.

- [ ] **Step 3: Manually verify in the browser**

Run the app, log in as a user holding one of `PAYROLL_VIEW_EMPLOYEE_CONFIG` / `PAYROLL_MANAGE_EMPLOYEE_CONFIG` / `PAYROLL_MANAGE_SALARY_ADVANCES` / `PAYROLL_SUPER_ADMIN`, open `/payroll/admin`, confirm the "Historique et contrôle" (or equivalent label under `PAYROLL.ADMIN_HOME.CARDS.AUDIT`) card is now visible and opens the existing table correctly (this was already working code before being hidden — this step just confirms nothing bit-rotted while it sat disconnected).

- [ ] **Step 4: Commit**

```bash
git add src/app/modules/parameter-sets/parameter-sets.component.ts
git commit -m "fix(payroll): re-enable the audit admin-home card

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Add the `GENERIC` source to `PayrollAdminService.audit()`

**Files:**
- Create: `src/main/java/com/daf360/payroll/modules/admin/entity/AuditTrail.java`
- Create: `src/main/java/com/daf360/payroll/modules/admin/repository/AuditTrailRepository.java`
- Modify: `src/main/java/com/daf360/payroll/modules/admin/dto/AuditEntryDto.java`
- Modify: `src/main/java/com/daf360/payroll/modules/admin/service/PayrollAdminService.java`
- Modify: `src/main/java/com/daf360/payroll/modules/admin/controller/PayrollAdminController.java`
- Test: `src/test/java/com/daf360/payroll/modules/admin/service/PayrollAdminServiceTest.java`

**Known limitation, documented not silently fixed:** `dbo.audit_trail` has no `pays_id` column of its own (it is table-agnostic by design — see the design spec). Country-scoping for `GENERIC` entries is done by extracting `pays_id` from the captured row JSON via `JSON_VALUE`, which only works for tables that actually have a `pays_id` column (e.g. `payroll_countries`, `salary_advance`...). Rows from any table without one will never match a specific-country filter. This is an acceptable v1 limitation for a per-country admin screen, not a bug to chase here.

- [ ] **Step 1: Write the entity**

```java
package com.daf360.payroll.modules.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_trail")
@Getter @Setter
public class AuditTrail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "pk_json", nullable = false)
    private String pkJson;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "changed_by")
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}
```

- [ ] **Step 2: Write the repository with the pays-scoped native query**

```java
package com.daf360.payroll.modules.admin.repository;

import com.daf360.payroll.modules.admin.entity.AuditTrail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditTrailRepository extends JpaRepository<AuditTrail, Long> {

    /**
     * Entries whose captured row JSON has a pays_id matching the caller's country.
     * Tables with no pays_id column never match -- see the service's javadoc.
     */
    @Query(value = """
            SELECT * FROM dbo.audit_trail
            WHERE TRY_CAST(JSON_VALUE(COALESCE(new_value, old_value), '$.pays_id') AS BIGINT) = :paysId
            ORDER BY changed_at DESC
            """, nativeQuery = true)
    Page<AuditTrail> findByPaysId(@Param("paysId") Long paysId, Pageable pageable);
}
```

- [ ] **Step 3: Extend `AuditEntryDto`**

```java
package com.daf360.payroll.modules.admin.dto;

import java.time.OffsetDateTime;

/**
 * One line of the payroll change log (« Journal des modifications »), flattened from one of
 * the history tables so the admin screen can list them together, newest first.
 *
 * @param source      {@code EMPLOYEE_CONFIG} (employee_payroll_config_history),
 *                    {@code SALARY_ADVANCE} (salary_advance_history), or
 *                    {@code GENERIC} (audit_trail -- any other in-scope table)
 * @param subjectId   the employee's user id (config) or the advance id (advance); null for GENERIC
 * @param subjectName the employee concerned, from users_ref -- null when unknown;
 *                    for GENERIC, "<table_name> <pk_json>"
 * @param action      config: the change reason; advance: the status reached ({@code to_status});
 *                    GENERIC: INSERT / UPDATE / DELETE
 * @param fromStatus  advance only: the status left, null otherwise (and on creation)
 * @param detail      config: contract type · gross salary; advance: the actor's notes;
 *                    GENERIC: the table name
 */
public record AuditEntryDto(
        String source,
        Long id,
        OffsetDateTime at,
        Long paysId,
        Long actorId,
        String actorName,
        Long subjectId,
        String subjectName,
        String action,
        String fromStatus,
        String detail
) {}
```
(Only the javadoc changes; the record's shape is unchanged, so no existing caller needs adapting beyond what Step 4 touches directly.)

- [ ] **Step 4: Extend `PayrollAdminService`**

Add the import, the constant, the repository field, and a fourth source in `audit(...)`:

```java
import com.daf360.payroll.modules.admin.entity.AuditTrail;
import com.daf360.payroll.modules.admin.repository.AuditTrailRepository;
```

```java
    public static final String SOURCE_EMPLOYEE_CONFIG = "EMPLOYEE_CONFIG";
    public static final String SOURCE_SALARY_ADVANCE  = "SALARY_ADVANCE";
    public static final String SOURCE_GENERIC         = "GENERIC";
```

Add `private final AuditTrailRepository auditTrailRepo;` to the field list (Lombok's `@RequiredArgsConstructor` picks it up automatically -- no manual constructor to edit).

Change the method signature and body:

```java
    public List<AuditEntryDto> audit(Long paysId, int limit, boolean withConfig, boolean withAdvances, boolean withGeneric) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        PageRequest page = PageRequest.of(0, size);

        List<EmployeePayrollConfigHistory> configs = withConfig
                ? configHistoryRepo.findByPaysIdOrderByChangedAtDesc(paysId, page) : List.of();
        List<SalaryAdvanceHistory> advances = withAdvances
                ? advanceHistoryRepo.findLatestByPaysId(paysId, page) : List.of();
        List<AuditTrail> generic = withGeneric
                ? auditTrailRepo.findByPaysId(paysId, page).getContent() : List.of();

        Map<Long, SalaryAdvance> advanceById = advanceRepo
                .findAllById(advances.stream().map(SalaryAdvanceHistory::getSalaryAdvanceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SalaryAdvance::getId, Function.identity()));

        Set<Long> userIds = new HashSet<>();
        configs.forEach(h -> { userIds.add(h.getChangedBy()); userIds.add(h.getProfileUserId()); });
        advances.forEach(h -> userIds.add(h.getActorUserId()));
        advanceById.values().forEach(a -> userIds.add(a.getProfileUserId()));
        Map<Long, String> names = names(userIds);

        List<AuditEntryDto> out = new ArrayList<>(configs.size() + advances.size() + generic.size());
        for (EmployeePayrollConfigHistory h : configs) {
            out.add(new AuditEntryDto(
                    SOURCE_EMPLOYEE_CONFIG, h.getId(), h.getChangedAt(), h.getPaysId(),
                    h.getChangedBy(), names.get(h.getChangedBy()),
                    h.getProfileUserId(), names.get(h.getProfileUserId()),
                    h.getReason(), null,
                    h.getCurrentGrossSalary() == null
                            ? h.getContractType()
                            : h.getContractType() + " · " + h.getCurrentGrossSalary().toPlainString()));
        }
        for (SalaryAdvanceHistory h : advances) {
            SalaryAdvance a = advanceById.get(h.getSalaryAdvanceId());
            Long employee = a == null ? null : a.getProfileUserId();
            out.add(new AuditEntryDto(
                    SOURCE_SALARY_ADVANCE, h.getId(), h.getCreatedAt(), paysId,
                    h.getActorUserId(), names.get(h.getActorUserId()),
                    h.getSalaryAdvanceId(), names.get(employee),
                    h.getToStatus(), h.getFromStatus(), h.getNotes()));
        }
        for (AuditTrail g : generic) {
            out.add(new AuditEntryDto(
                    SOURCE_GENERIC, g.getId(), g.getChangedAt(), paysId,
                    null, g.getChangedBy(),
                    null, g.getTableName() + " " + g.getPkJson(),
                    actionLabel(g.getAction()), null, g.getTableName()));
        }
        out.sort(Comparator.comparing(AuditEntryDto::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > size ? out.subList(0, size) : out;
    }

    private static String actionLabel(String code) {
        return switch (code) {
            case "I" -> "INSERT";
            case "U" -> "UPDATE";
            case "D" -> "DELETE";
            default -> code;
        };
    }
```

Note `actorName` for GENERIC is set directly from `g.getChangedBy()` (already a plain username string from `SESSION_CONTEXT`, not a numeric user id needing a `names()` lookup like the other two sources) -- `actorId` stays `null` for GENERIC because there is no numeric id to carry.

- [ ] **Step 5: Update the controller**

```java
    @GetMapping("/audit")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','"
        + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "','"
        + PermissionCatalog.MANAGE_SALARY_ADVANCES + "','"
        + PermissionCatalog.SUPER_ADMIN + "')")
    public List<AuditEntryDto> audit(@RequestParam Long paysId,
                                     @RequestParam(defaultValue = "200") int limit,
                                     Authentication auth) {
        Set<String> codes = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        boolean superAdmin = codes.contains(PermissionCatalog.SUPER_ADMIN);
        boolean withConfig = superAdmin
                || codes.contains(PermissionCatalog.VIEW_EMPLOYEE_CONFIG)
                || codes.contains(PermissionCatalog.MANAGE_EMPLOYEE_CONFIG);
        boolean withAdvances = superAdmin || codes.contains(PermissionCatalog.MANAGE_SALARY_ADVANCES);
        // GENERIC exposes raw field-level changes across every in-scope table -- gated to
        // super admin only for v1, a broader privilege than the two narrower existing sources.
        boolean withGeneric = superAdmin;
        return service.audit(paysId, limit, withConfig, withAdvances, withGeneric);
    }
```

- [ ] **Step 6: Update the existing test's call sites and add new ones**

In `PayrollAdminServiceTest.java`:
1. Add `@Mock AuditTrailRepository auditTrailRepo;` to the mocks.
2. Update `setUp()`:
```java
        service = new PayrollAdminService(countryRepo, paysRefRepo, usersRefRepo,
                configHistoryRepo, advanceHistoryRepo, advanceRepo, auditTrailRepo, userContext);
```
3. Update the two existing `audit(...)` calls to pass the new parameter:
   - `service.audit(179L, 50, true, true)` → `service.audit(179L, 50, true, true, false)`
   - `service.audit(179L, 50, false, false)` → `service.audit(179L, 50, false, false, false)`
4. In `audit_mergesBothSourcesNewestFirst_withNames`, since `withGeneric` is now `false`, add `lenient().when(auditTrailRepo.findByPaysId(any(), any())).thenReturn(Page.empty());` is NOT needed (it's `false`, so the repo is never called) -- but DO add `verify(auditTrailRepo, never()).findByPaysId(any(), any());` to the existing `audit_skipsSourcesTheCallerMayNotRead` test, mirroring its two existing `verify(..., never())` lines.
5. Add a new test:

```java
    @Test
    void audit_includesGenericSource_whenRequested() {
        AuditTrail generic = new AuditTrail();
        generic.setId(30L);
        generic.setTableName("payroll_countries");
        generic.setPkJson("{\"pays_id\":179}");
        generic.setAction("U");
        generic.setChangedBy("alice");
        generic.setChangedAt(T0.plusHours(2));

        when(auditTrailRepo.findByPaysId(eq(179L), any())).thenReturn(new PageImpl<>(List.of(generic)));

        List<AuditEntryDto> log = service.audit(179L, 50, false, false, true);

        assertEquals(1, log.size());
        AuditEntryDto entry = log.get(0);
        assertEquals(PayrollAdminService.SOURCE_GENERIC, entry.source());
        assertEquals("UPDATE", entry.action());
        assertEquals("payroll_countries", entry.detail());
        assertEquals("payroll_countries {\"pays_id\":179}", entry.subjectName());
        assertEquals("alice", entry.actorName());
        assertNull(entry.actorId());
    }
```
Add the matching imports: `com.daf360.payroll.modules.admin.entity.AuditTrail`, `com.daf360.payroll.modules.admin.repository.AuditTrailRepository`, `org.springframework.data.domain.Page`, `org.springframework.data.domain.PageImpl`.

- [ ] **Step 7: Run the full test file**

```bash
cd daf360-payroll-service
mvn test -Dtest=PayrollAdminServiceTest
```
Expected: `Tests run: 9, Failures: 0, Errors: 0` (the original 8 plus the new one).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/admin/entity/AuditTrail.java \
        src/main/java/com/daf360/payroll/modules/admin/repository/AuditTrailRepository.java \
        src/main/java/com/daf360/payroll/modules/admin/dto/AuditEntryDto.java \
        src/main/java/com/daf360/payroll/modules/admin/service/PayrollAdminService.java \
        src/main/java/com/daf360/payroll/modules/admin/controller/PayrollAdminController.java \
        src/test/java/com/daf360/payroll/modules/admin/service/PayrollAdminServiceTest.java
git commit -m "feat(audit): merge audit_trail into the payroll change log as a GENERIC source

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Render the `GENERIC` source in the frontend

**Files:**
- Modify: `daf360-payroll-frontend/src/app/core/payroll-api.service.ts:355`
- Modify: `daf360-payroll-frontend/src/app/modules/parameter-sets/admin/payroll-audit-admin.component.ts`
- Modify: `daf360-payroll-frontend/public/i18n/fr.json`
- Modify: `daf360-payroll-frontend/public/i18n/en.json`

- [ ] **Step 1: Widen the type**

In `payroll-api.service.ts`, change:
```typescript
  source: 'EMPLOYEE_CONFIG' | 'SALARY_ADVANCE';
```
to:
```typescript
  source: 'EMPLOYEE_CONFIG' | 'SALARY_ADVANCE' | 'GENERIC';
```

- [ ] **Step 2: Add the translation keys**

In `public/i18n/fr.json`, inside the existing `"SOURCE": { ... }` block (line ~1796):
```json
        "SOURCE": {
          "EMPLOYEE_CONFIG": "Configuration",
          "SALARY_ADVANCE": "Avance",
          "GENERIC": "Modification technique"
        },
```
In `public/i18n/en.json`, find the matching `SOURCE` block under the same `PAYROLL.ADMIN_HOME.AUDIT` path and add:
```json
        "SOURCE": {
          "EMPLOYEE_CONFIG": "Configuration",
          "SALARY_ADVANCE": "Advance",
          "GENERIC": "Technical change"
        },
```
(Re-check the exact existing English wording for the first two keys before adding the third, so the new one matches the established tone rather than guessing.)

- [ ] **Step 3: Extend the component's branching logic**

In `payroll-audit-admin.component.ts`:

`actionLabel()` -- add the GENERIC case explicitly rather than falling through:
```typescript
  private actionLabel(e: AuditEntryDto): string {
    if (e.source === 'EMPLOYEE_CONFIG' || e.source === 'GENERIC') return e.action;
    return e.fromStatus
      ? `${this.statusLabel(e.fromStatus)} → ${this.statusLabel(e.action)}`
      : this.t('PAYROLL.ADMIN_HOME.AUDIT.CREATED', { status: this.statusLabel(e.action) });
  }
```

The badge variant in `rows()` -- extend the ternary to three cases:
```typescript
        type: {
          label: this.sourceLabel(e.source),
          options: { variant: e.source === 'EMPLOYEE_CONFIG' ? 'info' : e.source === 'SALARY_ADVANCE' ? 'warning' : 'neutral', size: 'sm' },
        } satisfies BadgeCell,
```

`subjectLabel()` needs no change -- its `SALARY_ADVANCE`-specific branch already falls through to `name` (i.e. `subjectName`) for any other source, and the backend already builds `subjectName` as `"<table_name> <pk_json>"` for GENERIC rows.

- [ ] **Step 4: Verify it builds**

```bash
cd daf360-payroll-frontend
npx ng build --configuration development
```
Expected: clean build, no new errors or warnings.

- [ ] **Step 5: Manual verification**

With Task 3's actor capture and Task 5's backend change both live, open `/payroll/admin?tab=audit` as a super-admin user, pick a country, and confirm rows tagged "Modification technique" (or "Technical change") appear for any Task 2 Step 4 test writes still in `audit_trail`, with the real actor name shown.

- [ ] **Step 6: Commit**

```bash
git add src/app/core/payroll-api.service.ts \
        src/app/modules/parameter-sets/admin/payroll-audit-admin.component.ts \
        public/i18n/fr.json public/i18n/en.json
git commit -m "feat(audit): render the GENERIC audit_trail source in the admin journal

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:** full Payroll trigger rollout (Task 2) ✓, actor capture wired into the service (Task 3) ✓, orphaned screen revived (Task 4) ✓, backend merge of the new source (Task 5) ✓, frontend rendering of the new source (Task 6) ✓. Exclusion list determined from real schema inspection, not guessed, with explicit reasoning for what's NOT excluded (Task 2's header).

**Placeholder scan:** no TBD/TODO; every step has complete, real code matching the actual current files read during this planning pass.

**Type/name consistency:** `SOURCE_GENERIC = "GENERIC"` (backend) matches `'GENERIC'` (frontend type) and the new i18n key `SOURCE.GENERIC`. `AuditTrail` entity field names (`tableName`, `pkJson`, `action`, `changedBy`, `changedAt`) match `dbo.audit_trail`'s real columns from Phase 0's migration exactly. `audit(...)`'s new 5-parameter signature is updated consistently across the service, its test, and the controller's one call site -- no other caller of this method exists (confirmed via the single controller endpoint).

**Contradicted assumptions found during planning (reported, not just quietly fixed):** none of substance -- `PAYROLL_AUDIT_PERMISSIONS` exists exactly as expected and already matches the backend's real permission codes; the `?tab=audit` template case was never commented out (only the nav card was); the DataSource is autoconfigured as assumed. The one genuine gap found: `dbo.audit_trail` has no `pays_id` column, which the original task description didn't anticipate -- resolved via `JSON_VALUE` extraction, documented above as a known v1 limitation rather than silently glossed over.
