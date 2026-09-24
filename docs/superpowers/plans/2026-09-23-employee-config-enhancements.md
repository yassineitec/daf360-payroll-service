# Employee Config Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a selectable employee list to the Payroll config page, a "Salaire brut" field with an on-demand net-salary calculation (via the existing simulation engine), and a per-employee "primes exceptionnelles" (bonus) list — editable from both the Payroll config page and the RH profile's Rémunération tab.

**Architecture:** Extends the existing `EmployeePayrollConfig` entity/DTO family (already shared by `daf360-payroll-frontend` and `daf360-rh-frontend`, both calling `daf360-payroll-service` directly) with a new nullable `currentGrossSalary` column, a new non-persisting `calculate-net` endpoint that delegates to the already-built `PayrollSimulatorService.computeFromGross()`, and a new sibling `employee_payroll_bonus` table with its own CRUD endpoints and repository/service/controller, mirroring the existing `EmployeePayrollConfig*` classes' structure exactly.

**Tech Stack:** Spring Boot / JPA / SQL Server (`daf360-payroll-service`), Angular standalone components with signals (`daf360-payroll-frontend`, `daf360-rh-frontend`).

---

## Design reference

Full design: `docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md` (this repo).

## Current-state facts this plan relies on (verified by reading the actual files, 2026-09-23)

- `EmployeePayrollConfig` entity (`src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfig.java`) has fields `id, profileUserId, paysId, contractType, selectedBenefitCodes (String JSON), currentNetSalary (BigDecimal), updatedBy, updatedAt, createdAt`.
- `EmployeePayrollConfigDto` is a record: `(Long profileUserId, Long paysId, String contractType, List<String> selectedBenefitCodes, BigDecimal currentNetSalary, Long updatedBy, OffsetDateTime updatedAt)`.
- `EmployeePayrollConfigService.getOrDefault()` returns `new EmployeePayrollConfigDto(profileUserId, paysId, contractType, List.of(), null, null, null)` for a never-configured employee (7 args, matching the 7-field DTO above).
- `PayrollSimulatorService.computeFromGross(BigDecimal gross, ParameterSet ps, List<SocialChargeRate> rates, List<BenefitCatalogue> benefits, List<PayrollRubrique> rubriques, String contractType, int joursTravailes)` returns a `PayrollResult` record whose net figure is `result.netInHand()` (BigDecimal).
- `ParameterSetService` (already used by `IndividualSimulationService`) exposes `loadActiveEntity(paysId)`, `loadRates(parameterSetId)`, `loadBenefits(parameterSetId)`, `loadRubriques(parameterSetId)`.
- `PermissionCatalog` constants: `VIEW_EMPLOYEE_CONFIG = "PAYROLL_VIEW_EMPLOYEE_CONFIG"`, `MANAGE_EMPLOYEE_CONFIG = "PAYROLL_MANAGE_EMPLOYEE_CONFIG"`.
- No test file exists anywhere for the `employeeconfig` module today — Tasks 2 and 4 below create the first ones.
- `daf360-payroll-frontend`'s `<app-employee-select>` (`src/app/shared/employee-select/employee-select.component.ts`) is backed by `HrProfileService.searchEmployees(search, paysId, size)` (`src/app/core/hr-profile.service.ts`), returning `EmployeePage { content: EmployeeListItem[]; totalElements; totalPages; last }`, with `EmployeeListItem { userId, profileId, fullName, employeeId, paysId, paysLabel, contractType, department, lifecycleStatus }`.
- `daf360-rh-frontend`'s `remuneration.service.ts` / `remuneration-section.component.ts` duplicate the exact same DTO shapes and endpoint calls as `daf360-payroll-frontend`'s `employee-config.service.ts` / `.component.ts`, minus any employee list (it always operates on the one profile it's embedded in).

## Standing constraints for this session

- **`daf360-payroll-service`, `daf360-payroll-frontend`, `daf360-rh-frontend` must NOT be committed or pushed at any point during this plan**, for any task, without new explicit user permission. Every task ends with "confirm scope via `git status`/`git diff` — do NOT stage or commit."
- Backend verification: `mvn clean test` (not bare `mvn test` — this session has hit a stale false-positive "Nothing to compile" from a bare `mvn test`/`mvn compile` more than once).
- Frontend verification: the real `npm run build -- --configuration development` (matching each repo's actual Docker build command) — `tsc --noEmit` alone has been proven insufficient in this session (a real type error passed `tsc --noEmit` but failed the real Angular build).
- If any task's "Find (confirmed current content)" block doesn't match the file's actual current content exactly, the implementer must NOT guess or adapt silently — report NEEDS_CONTEXT with the actual current content.

---

### Task 1: Migration — gross salary column + bonus table

**Files:**
- Create: `daf360-payroll-service/src/main/resources/db/migration/V24__employee_config_gross_salary_and_bonuses.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V24__employee_config_gross_salary_and_bonuses.sql
--
-- Adds current_gross_salary alongside the existing current_net_salary on
-- employee_payroll_config / employee_payroll_config_history, and a new
-- employee_payroll_bonus table for one-off "primes exceptionnelles" per employee.
-- Bonuses are tracked only — not fed into PayrollSimulatorService/TopologicalEvaluator.
-- See docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md.

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.employee_payroll_config') AND name = 'current_gross_salary'
)
BEGIN
    ALTER TABLE [dbo].[employee_payroll_config] ADD [current_gross_salary] NUMERIC(18,3) NULL;
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('dbo.employee_payroll_config_history') AND name = 'current_gross_salary'
)
BEGIN
    ALTER TABLE [dbo].[employee_payroll_config_history] ADD [current_gross_salary] NUMERIC(18,3) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'employee_payroll_bonus')
BEGIN
    CREATE TABLE [dbo].[employee_payroll_bonus] (
        [id]                BIGINT IDENTITY(1,1)  NOT NULL,
        [profile_user_id]   BIGINT                NOT NULL,
        [amount]            NUMERIC(18,3)         NOT NULL,
        [currency]          VARCHAR(3)            NOT NULL,
        [period_month]      TINYINT               NOT NULL,
        [period_year]       SMALLINT              NOT NULL,
        [label]             NVARCHAR(255)         NOT NULL,
        [comment]           NVARCHAR(1000)        NULL,
        [created_by]        BIGINT                NOT NULL,
        [created_at]        DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_employee_payroll_bonus] PRIMARY KEY ([id]),
        CONSTRAINT [CK_epb_period_month] CHECK ([period_month] BETWEEN 1 AND 12)
    );
    CREATE INDEX [IX_epb_profile_user_id] ON [dbo].[employee_payroll_bonus] ([profile_user_id]);
END
GO
```

- [ ] **Step 2: Apply it by hand against the real dev DB**

This dev DB doesn't auto-run Flyway past its current version. Use the established pattern: a small mssql-jdbc Java program run via PowerShell, reading `DB_USER`/`DB_PASSWORD` from `c:/Users/ITEC2/OneDrive/Documents/projects/.env` at runtime — never print the credential value.

Write `ApplyV24.java` in the session scratchpad directory:

```java
import java.sql.*;
import java.nio.file.*;
import java.util.*;

public class ApplyV24 {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get("C:/Users/ITEC2/OneDrive/Documents/projects/.env"))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        String user = env.get("DB_USER");
        String pass = env.get("DB_PASSWORD");
        String url = "jdbc:sqlserver://localhost:1433;databaseName=DAF360_PAYROLL;encrypt=false;trustServerCertificate=true";

        String sql = Files.readString(Paths.get(
            "c:/Users/ITEC2/OneDrive/Documents/projects/daf360-payroll-service/src/main/resources/db/migration/V24__employee_config_gross_salary_and_bonuses.sql"));

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            for (String batch : sql.split("(?m)^GO\\s*$")) {
                String trimmed = batch.trim();
                if (trimmed.isEmpty()) continue;
                try (Statement st = conn.createStatement()) {
                    st.execute(trimmed);
                }
            }
        }
        System.out.println("V24 applied.");
    }
}
```

Compile and run (adjust the mssql-jdbc jar path to whatever version is present under `C:\Users\ITEC2\.m2\repository\com\microsoft\sqlserver\mssql-jdbc\`):

```powershell
$jar = "C:\Users\ITEC2\.m2\repository\com\microsoft\sqlserver\mssql-jdbc\12.10.2.jre11\mssql-jdbc-12.10.2.jre11.jar"
javac -cp $jar ApplyV24.java
java -cp "$jar;." ApplyV24
```

Expected output: `V24 applied.`

- [ ] **Step 3: Verify the schema change directly**

Run a follow-up query (same connection pattern) confirming: `SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('dbo.employee_payroll_config') AND name = 'current_gross_salary'` returns `1`, and `SELECT COUNT(*) FROM sys.tables WHERE name = 'employee_payroll_bonus'` returns `1`.

- [ ] **Step 4: Confirm scope, do NOT stage or commit**

```bash
git status --short
```
Expected: only the new migration file listed as `??`. Do not run `git add` or `git commit`.

---

### Task 2: Backend — `currentGrossSalary` on the config entity/DTO chain (TDD)

**Files:**
- Modify: `src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfig.java`
- Modify: `src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfigHistory.java`
- Modify: `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollConfigDto.java`
- Modify: `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/UpsertEmployeePayrollConfigRequest.java`
- Modify: `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollConfigHistoryDto.java`
- Modify: `src/main/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollConfigService.java`
- Create: `src/test/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollConfigServiceTest.java`

- [ ] **Step 1: Write the failing test (new file — no test existed for this module before)**

```java
package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeePayrollConfigServiceTest {

    @Mock EmployeePayrollConfigRepository repo;
    @Mock EmployeePayrollConfigHistoryRepository historyRepo;
    @Mock HrEmployeeClient hrEmployeeClient;
    @Mock ParameterSetService paramSetService;
    @Mock PayrollSimulatorService simulatorService;

    private EmployeePayrollConfigService service() {
        return new EmployeePayrollConfigService(
                repo, historyRepo, hrEmployeeClient, new ObjectMapper(), paramSetService, simulatorService);
    }

    @Test
    void getOrDefault_neverConfigured_returnsDefaultWithNullGrossAndNetSalary() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(hrEmployeeClient.findEmployeeByUserId(10L)).thenReturn(Optional.empty());

        EmployeePayrollConfigDto dto = service().getOrDefault(10L);

        assertNull(dto.currentGrossSalary());
        assertNull(dto.currentNetSalary());
    }

    @Test
    void upsert_savesGrossSalaryOnBothEntityAndHistory() {
        when(repo.findByProfileUserId(10L)).thenReturn(Optional.empty());
        when(repo.save(any(EmployeePayrollConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        UpsertEmployeePayrollConfigRequest req = new UpsertEmployeePayrollConfigRequest(
                179L, "CDI", List.of(), new BigDecimal("5000.000"), new BigDecimal("3800.000"), "Ajustement");

        EmployeePayrollConfigDto result = service().upsert(10L, req, 99L);

        assertEquals(new BigDecimal("5000.000"), result.currentGrossSalary());
        assertEquals(new BigDecimal("3800.000"), result.currentNetSalary());

        ArgumentCaptor<com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory> historyCaptor =
                ArgumentCaptor.forClass(com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory.class);
        org.mockito.Mockito.verify(historyRepo).save(historyCaptor.capture());
        assertEquals(new BigDecimal("5000.000"), historyCaptor.getValue().getCurrentGrossSalary());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-payroll-service
./mvnw clean test -Dtest=EmployeePayrollConfigServiceTest
```
Expected: compile failure — `currentGrossSalary()` doesn't exist on `EmployeePayrollConfigDto`, and the `EmployeePayrollConfigService` constructor doesn't accept `ParameterSetService`/`PayrollSimulatorService` yet.

- [ ] **Step 3: Add `currentGrossSalary` to the entity**

Find (confirmed current content, `EmployeePayrollConfig.java` lines 31-33):
```java
    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "updated_by", nullable = false)
```

Replace:
```java
    @Column(name = "current_gross_salary")
    private BigDecimal currentGrossSalary;

    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "updated_by", nullable = false)
```

- [ ] **Step 4: Add `currentGrossSalary` to the history entity**

Find (confirmed current content, `EmployeePayrollConfigHistory.java` lines 31-34):
```java
    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "reason", nullable = false)
```

Replace:
```java
    @Column(name = "current_gross_salary")
    private BigDecimal currentGrossSalary;

    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "reason", nullable = false)
```

- [ ] **Step 5: Add `currentGrossSalary` to `EmployeePayrollConfigDto`**

Find (confirmed current content, whole file):
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigDto(
        Long profileUserId,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentNetSalary,
        Long updatedBy,
        OffsetDateTime updatedAt
) {}
```

Replace:
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigDto(
        Long profileUserId,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        Long updatedBy,
        OffsetDateTime updatedAt
) {}
```

- [ ] **Step 6: Add `currentGrossSalary` to `UpsertEmployeePayrollConfigRequest`**

Find (confirmed current content, whole file):
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record UpsertEmployeePayrollConfigRequest(
        @NotNull Long paysId,
        @NotNull String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentNetSalary,
        @NotBlank String reason
) {}
```

Replace:
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record UpsertEmployeePayrollConfigRequest(
        @NotNull Long paysId,
        @NotNull String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        @NotBlank String reason
) {}
```

- [ ] **Step 7: Add `currentGrossSalary` to `EmployeePayrollConfigHistoryDto`**

Find (confirmed current content, whole file):
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigHistoryDto(
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentNetSalary,
        String reason,
        Long changedBy,
        OffsetDateTime changedAt
) {}
```

Replace:
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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

- [ ] **Step 8: Update `EmployeePayrollConfigService` — constructor, `getOrDefault`, `upsert`, `toDto`, `toHistoryDto`**

Find (confirmed current content, imports + fields + constructor, lines 1-40):
```java
package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigHistoryDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Per-employee payroll configuration — the first per-EMPLOYEE (not per-country) scoping
 * anywhere in this service. See
 * docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md.
 */
@Service
public class EmployeePayrollConfigService {

    private final EmployeePayrollConfigRepository repo;
    private final EmployeePayrollConfigHistoryRepository historyRepo;
    private final HrEmployeeClient hrEmployeeClient;
    private final ObjectMapper objectMapper;

    public EmployeePayrollConfigService(EmployeePayrollConfigRepository repo,
                                         EmployeePayrollConfigHistoryRepository historyRepo,
                                         HrEmployeeClient hrEmployeeClient,
                                         ObjectMapper objectMapper) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.hrEmployeeClient = hrEmployeeClient;
        this.objectMapper = objectMapper;
    }
```

Replace:
```java
package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigHistoryDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Per-employee payroll configuration — the first per-EMPLOYEE (not per-country) scoping
 * anywhere in this service. See
 * docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md and
 * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md.
 */
@Service
public class EmployeePayrollConfigService {

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

Find (confirmed current content, `getOrDefault`'s return line):
```java
        return new EmployeePayrollConfigDto(profileUserId, paysId, contractType, List.of(), null, null, null);
    }
```

Replace:
```java
        return new EmployeePayrollConfigDto(profileUserId, paysId, contractType, List.of(), null, null, null, null);
    }

    /**
     * Derives net salary from a given gross using the same simulation engine
     * IndividualSimulationService uses, for this employee's own configured country,
     * contract type and selected benefits. Deliberately does NOT persist a SimulationResult
     * row — that table is real simulation-run history; this is a thin, non-persisting
     * calculation used to fill the net field while editing this screen. See
     * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md.
     */
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

        PayrollSimulatorService.PayrollResult result = simulatorService.computeFromGross(
                grossSalary, ps, rates, benefits, rubriques, cfg.contractType(), 22);

        return new CalculateNetResponse(result.netInHand());
    }
```

Find (confirmed current content, inside `upsert`):
```java
        entity.setPaysId(req.paysId());
        entity.setContractType(req.contractType());
        entity.setSelectedBenefitCodes(benefitCodesJson);
        entity.setCurrentNetSalary(req.currentNetSalary());
        entity.setUpdatedBy(actingUserId);
        entity.setUpdatedAt(now);
        entity = repo.save(entity);

        EmployeePayrollConfigHistory history = new EmployeePayrollConfigHistory();
        history.setProfileUserId(profileUserId);
        history.setPaysId(req.paysId());
        history.setContractType(req.contractType());
        history.setSelectedBenefitCodes(benefitCodesJson);
        history.setCurrentNetSalary(req.currentNetSalary());
        history.setReason(req.reason());
```

Replace:
```java
        entity.setPaysId(req.paysId());
        entity.setContractType(req.contractType());
        entity.setSelectedBenefitCodes(benefitCodesJson);
        entity.setCurrentGrossSalary(req.currentGrossSalary());
        entity.setCurrentNetSalary(req.currentNetSalary());
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
```

Find (confirmed current content, `toDto`/`toHistoryDto`):
```java
    private EmployeePayrollConfigDto toDto(EmployeePayrollConfig e) {
        return new EmployeePayrollConfigDto(
                e.getProfileUserId(), e.getPaysId(), e.getContractType(),
                fromJson(e.getSelectedBenefitCodes()), e.getCurrentNetSalary(),
                e.getUpdatedBy(), e.getUpdatedAt());
    }

    private EmployeePayrollConfigHistoryDto toHistoryDto(EmployeePayrollConfigHistory h) {
        return new EmployeePayrollConfigHistoryDto(
                h.getPaysId(), h.getContractType(), fromJson(h.getSelectedBenefitCodes()),
                h.getCurrentNetSalary(), h.getReason(), h.getChangedBy(), h.getChangedAt());
    }
```

Replace:
```java
    private EmployeePayrollConfigDto toDto(EmployeePayrollConfig e) {
        return new EmployeePayrollConfigDto(
                e.getProfileUserId(), e.getPaysId(), e.getContractType(),
                fromJson(e.getSelectedBenefitCodes()), e.getCurrentGrossSalary(), e.getCurrentNetSalary(),
                e.getUpdatedBy(), e.getUpdatedAt());
    }

    private EmployeePayrollConfigHistoryDto toHistoryDto(EmployeePayrollConfigHistory h) {
        return new EmployeePayrollConfigHistoryDto(
                h.getPaysId(), h.getContractType(), fromJson(h.getSelectedBenefitCodes()),
                h.getCurrentGrossSalary(), h.getCurrentNetSalary(), h.getReason(), h.getChangedBy(), h.getChangedAt());
    }
```

- [ ] **Step 9: Create the two new DTOs `calculateNet` needs**

Create `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/CalculateNetRequest.java`:
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CalculateNetRequest(
        @NotNull @Positive BigDecimal grossSalary
) {}
```

Create `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/CalculateNetResponse.java`:
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;

public record CalculateNetResponse(
        BigDecimal netInHand
) {}
```

- [ ] **Step 10: Run the tests to verify they pass**

```bash
./mvnw clean test -Dtest=EmployeePayrollConfigServiceTest
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 11: Confirm scope, do NOT stage or commit**

```bash
git status --short
```
Expected: the 5 modified files + 2 new DTO files + the new test file, all as plain `M`/`??`. Do not run `git add` or `git commit`.

---

### Task 3: Backend — `calculate-net` endpoint (TDD)

**Files:**
- Modify: `src/main/java/com/daf360/payroll/modules/employeeconfig/controller/EmployeePayrollConfigController.java`
- Create: `src/test/java/com/daf360/payroll/modules/employeeconfig/controller/EmployeePayrollConfigControllerTest.java`

- [ ] **Step 1: Write the failing test (new file — the controller has no test today)**

```java
package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetRequest;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollConfigService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeePayrollConfigControllerTest {

    @Mock EmployeePayrollConfigService service;
    @Mock UserContextService userContext;

    @Test
    void calculateNet_delegatesToServiceAndReturnsNetInHand() {
        when(service.calculateNet(10L, new BigDecimal("5000.000")))
                .thenReturn(new CalculateNetResponse(new BigDecimal("3820.500")));

        EmployeePayrollConfigController controller = new EmployeePayrollConfigController(service, userContext);
        CalculateNetResponse result = controller.calculateNet(10L, new CalculateNetRequest(new BigDecimal("5000.000")));

        assertEquals(new BigDecimal("3820.500"), result.netInHand());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw clean test -Dtest=EmployeePayrollConfigControllerTest
```
Expected: compile failure — `calculateNet` doesn't exist on `EmployeePayrollConfigController` yet.

- [ ] **Step 3: Add the endpoint**

Find (confirmed current content, whole file):
```java
package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigHistoryDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollConfigService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/employee-configs")
public class EmployeePayrollConfigController {

    private final EmployeePayrollConfigService service;
    private final UserContextService userContext;

    public EmployeePayrollConfigController(EmployeePayrollConfigService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping("/{profileUserId}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigDto get(@PathVariable Long profileUserId) {
        return service.getOrDefault(profileUserId);
    }

    @GetMapping("/{profileUserId}/history")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public List<EmployeePayrollConfigHistoryDto> history(@PathVariable Long profileUserId) {
        return service.getHistory(profileUserId);
    }

    @PutMapping("/{profileUserId}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigDto upsert(@PathVariable Long profileUserId,
                                            @Valid @RequestBody UpsertEmployeePayrollConfigRequest req) {
        return service.upsert(profileUserId, req, userContext.currentUserId());
    }
}
```

Replace:
```java
package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetRequest;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigHistoryDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollConfigService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/employee-configs")
public class EmployeePayrollConfigController {

    private final EmployeePayrollConfigService service;
    private final UserContextService userContext;

    public EmployeePayrollConfigController(EmployeePayrollConfigService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping("/{profileUserId}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigDto get(@PathVariable Long profileUserId) {
        return service.getOrDefault(profileUserId);
    }

    @GetMapping("/{profileUserId}/history")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public List<EmployeePayrollConfigHistoryDto> history(@PathVariable Long profileUserId) {
        return service.getHistory(profileUserId);
    }

    @PutMapping("/{profileUserId}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigDto upsert(@PathVariable Long profileUserId,
                                            @Valid @RequestBody UpsertEmployeePayrollConfigRequest req) {
        return service.upsert(profileUserId, req, userContext.currentUserId());
    }

    @PostMapping("/{profileUserId}/calculate-net")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public CalculateNetResponse calculateNet(@PathVariable Long profileUserId,
                                              @Valid @RequestBody CalculateNetRequest req) {
        return service.calculateNet(profileUserId, req.grossSalary());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw clean test -Dtest=EmployeePayrollConfigControllerTest
```
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Confirm scope, do NOT stage or commit**

```bash
git status --short
```
Do not run `git add` or `git commit`.

---

### Task 4: Backend — `EmployeePayrollBonus` CRUD (TDD)

**Files:**
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollBonus.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/repository/EmployeePayrollBonusRepository.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollBonusDto.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/CreateEmployeePayrollBonusRequest.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollBonusService.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/controller/EmployeePayrollBonusController.java`
- Create: `src/test/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollBonusServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.CreateEmployeePayrollBonusRequest;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollBonusDto;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollBonusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeePayrollBonusServiceTest {

    @Mock EmployeePayrollBonusRepository repo;

    @Test
    void create_savesAllFieldsAndReturnsDto() {
        when(repo.save(any(EmployeePayrollBonus.class))).thenAnswer(inv -> {
            EmployeePayrollBonus b = inv.getArgument(0);
            b.setId(1L);
            return b;
        });

        EmployeePayrollBonusService service = new EmployeePayrollBonusService(repo);
        CreateEmployeePayrollBonusRequest req = new CreateEmployeePayrollBonusRequest(
                new BigDecimal("500.000"), "TND", 12, 2026, "Prime de fin d'année", "Décision comité");

        EmployeePayrollBonusDto result = service.create(10L, req, 99L);

        assertEquals(1L, result.id());
        assertEquals(10L, result.profileUserId());
        assertEquals(new BigDecimal("500.000"), result.amount());
        assertEquals("TND", result.currency());
        assertEquals(12, result.periodMonth());
        assertEquals(2026, result.periodYear());
        assertEquals("Prime de fin d'année", result.label());
        assertEquals(99L, result.createdBy());
    }

    @Test
    void list_ordersByPeriodDescending() {
        when(repo.findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(10L)).thenReturn(List.of());

        List<EmployeePayrollBonusDto> result = new EmployeePayrollBonusService(repo).list(10L);

        assertEquals(0, result.size());
    }

    @Test
    void delete_delegatesToRepository() {
        EmployeePayrollBonusService service = new EmployeePayrollBonusService(repo);
        service.delete(5L);
        org.mockito.Mockito.verify(repo).deleteById(5L);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw clean test -Dtest=EmployeePayrollBonusServiceTest
```
Expected: compile failure — none of `EmployeePayrollBonus`, `EmployeePayrollBonusRepository`, `EmployeePayrollBonusDto`, `CreateEmployeePayrollBonusRequest`, `EmployeePayrollBonusService` exist yet.

- [ ] **Step 3: Create the entity**

```java
package com.daf360.payroll.modules.employeeconfig.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * "Prime exceptionnelle" — a one-off, per-employee bonus. Record only: not fed into
 * PayrollSimulatorService/TopologicalEvaluator (see
 * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md, Non-Goals).
 * Added or deleted, never edited in place — no updated_at/updated_by.
 */
@Entity
@Table(name = "employee_payroll_bonus")
@Getter @Setter
public class EmployeePayrollBonus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_user_id", nullable = false)
    private Long profileUserId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "period_month", nullable = false)
    private Integer periodMonth;

    @Column(name = "period_year", nullable = false)
    private Integer periodYear;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "comment")
    private String comment;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
```

- [ ] **Step 4: Create the repository**

```java
package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeePayrollBonusRepository extends JpaRepository<EmployeePayrollBonus, Long> {
    List<EmployeePayrollBonus> findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(Long profileUserId);
}
```

- [ ] **Step 5: Create the DTOs**

`src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollBonusDto.java`:
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record EmployeePayrollBonusDto(
        Long id,
        Long profileUserId,
        BigDecimal amount,
        String currency,
        Integer periodMonth,
        Integer periodYear,
        String label,
        String comment,
        Long createdBy,
        OffsetDateTime createdAt
) {}
```

`src/main/java/com/daf360/payroll/modules/employeeconfig/dto/CreateEmployeePayrollBonusRequest.java`:
```java
package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateEmployeePayrollBonusRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull @Min(1) @Max(12) Integer periodMonth,
        @NotNull Integer periodYear,
        @NotBlank String label,
        String comment
) {}
```

- [ ] **Step 6: Create the service**

```java
package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.CreateEmployeePayrollBonusRequest;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollBonusDto;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollBonusRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * "Primes exceptionnelles" — one-off, per-employee bonuses. Record only: not fed into
 * PayrollSimulatorService/TopologicalEvaluator (see
 * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md, Non-Goals).
 * Added or deleted, never edited in place.
 */
@Service
public class EmployeePayrollBonusService {

    private final EmployeePayrollBonusRepository repo;

    public EmployeePayrollBonusService(EmployeePayrollBonusRepository repo) {
        this.repo = repo;
    }

    public List<EmployeePayrollBonusDto> list(Long profileUserId) {
        return repo.findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(profileUserId).stream()
                .map(this::toDto)
                .toList();
    }

    public EmployeePayrollBonusDto create(Long profileUserId, CreateEmployeePayrollBonusRequest req, Long createdBy) {
        EmployeePayrollBonus entity = new EmployeePayrollBonus();
        entity.setProfileUserId(profileUserId);
        entity.setAmount(req.amount());
        entity.setCurrency(req.currency());
        entity.setPeriodMonth(req.periodMonth());
        entity.setPeriodYear(req.periodYear());
        entity.setLabel(req.label());
        entity.setComment(req.comment());
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(OffsetDateTime.now());
        return toDto(repo.save(entity));
    }

    public void delete(Long bonusId) {
        repo.deleteById(bonusId);
    }

    private EmployeePayrollBonusDto toDto(EmployeePayrollBonus e) {
        return new EmployeePayrollBonusDto(
                e.getId(), e.getProfileUserId(), e.getAmount(), e.getCurrency(),
                e.getPeriodMonth(), e.getPeriodYear(), e.getLabel(), e.getComment(),
                e.getCreatedBy(), e.getCreatedAt());
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
./mvnw clean test -Dtest=EmployeePayrollBonusServiceTest
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 8: Create the controller (no dedicated test — thin pass-through, same style as `EmployeePayrollConfigController`'s untested pass-through methods)**

```java
package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.CreateEmployeePayrollBonusRequest;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollBonusDto;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollBonusService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/employee-configs/{profileUserId}/bonuses")
public class EmployeePayrollBonusController {

    private final EmployeePayrollBonusService service;
    private final UserContextService userContext;

    public EmployeePayrollBonusController(EmployeePayrollBonusService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public List<EmployeePayrollBonusDto> list(@PathVariable Long profileUserId) {
        return service.list(profileUserId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollBonusDto create(@PathVariable Long profileUserId,
                                          @Valid @RequestBody CreateEmployeePayrollBonusRequest req) {
        return service.create(profileUserId, req, userContext.currentUserId());
    }

    @DeleteMapping("/{bonusId}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long profileUserId, @PathVariable Long bonusId) {
        service.delete(bonusId);
    }
}
```

- [ ] **Step 9: Confirm scope, do NOT stage or commit**

```bash
git status --short
```
Do not run `git add` or `git commit`.

---

### Task 5: Backend — full verification

**Files:** none (verification only)

- [ ] **Step 1: Full clean test run**

```bash
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-payroll-service
./mvnw clean test
```
Expected: `BUILD SUCCESS`, no failing tests anywhere in the module (this also re-verifies nothing else in the codebase broke from the constructor signature change on `EmployeePayrollConfigService`).

- [ ] **Step 2: Confirm scope, do NOT stage or commit**

```bash
git status --short
```
Expected: every file touched across Tasks 1-4, all as plain `M`/`??`, nothing staged. Do not run `git add` or `git commit`.

---

### Task 6: Frontend (Payroll) — service layer

**Files:**
- Modify: `daf360-payroll-frontend/src/app/modules/employee-config/employee-config.service.ts`

- [ ] **Step 1: Replace the whole file**

Find (confirmed current content, whole file):
```ts
import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface EmployeePayrollConfigDto {
  profileUserId: number;
  // Null on a never-configured employee whose country the cross-service HR lookup couldn't
  // resolve — the admin picks one explicitly via the country select in that case.
  paysId: number | null;
  contractType: string;
  selectedBenefitCodes: string[];
  currentNetSalary: number | null;
  updatedBy: number | null;
  updatedAt: string | null;
}

export interface UpsertEmployeePayrollConfigRequest {
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentNetSalary: number | null;
  reason: string;
}

@Injectable({ providedIn: 'root' })
export class EmployeeConfigService {
  private http = inject(HttpClient);
  private base = environment.payrollApiUrl + '/api/payroll/employee-configs';

  get(profileUserId: number): Observable<EmployeePayrollConfigDto> {
    return this.http.get<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`);
  }

  upsert(profileUserId: number, req: UpsertEmployeePayrollConfigRequest): Observable<EmployeePayrollConfigDto> {
    return this.http.put<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`, req);
  }
}
```

Replace:
```ts
import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface EmployeePayrollConfigDto {
  profileUserId: number;
  // Null on a never-configured employee whose country the cross-service HR lookup couldn't
  // resolve — the admin picks one explicitly via the country select in that case.
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

export interface CalculateNetResponse {
  netInHand: number;
}

export interface EmployeePayrollBonusDto {
  id: number;
  profileUserId: number;
  amount: number;
  currency: string;
  periodMonth: number;
  periodYear: number;
  label: string;
  comment: string | null;
  createdBy: number;
  createdAt: string;
}

export interface CreateEmployeePayrollBonusRequest {
  amount: number;
  currency: string;
  periodMonth: number;
  periodYear: number;
  label: string;
  comment: string | null;
}

@Injectable({ providedIn: 'root' })
export class EmployeeConfigService {
  private http = inject(HttpClient);
  private base = environment.payrollApiUrl + '/api/payroll/employee-configs';

  get(profileUserId: number): Observable<EmployeePayrollConfigDto> {
    return this.http.get<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`);
  }

  upsert(profileUserId: number, req: UpsertEmployeePayrollConfigRequest): Observable<EmployeePayrollConfigDto> {
    return this.http.put<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`, req);
  }

  calculateNet(profileUserId: number, grossSalary: number): Observable<CalculateNetResponse> {
    return this.http.post<CalculateNetResponse>(`${this.base}/${profileUserId}/calculate-net`, { grossSalary });
  }

  getBonuses(profileUserId: number): Observable<EmployeePayrollBonusDto[]> {
    return this.http.get<EmployeePayrollBonusDto[]>(`${this.base}/${profileUserId}/bonuses`);
  }

  createBonus(profileUserId: number, req: CreateEmployeePayrollBonusRequest): Observable<EmployeePayrollBonusDto> {
    return this.http.post<EmployeePayrollBonusDto>(`${this.base}/${profileUserId}/bonuses`, req);
  }

  deleteBonus(profileUserId: number, bonusId: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${profileUserId}/bonuses/${bonusId}`);
  }
}
```

- [ ] **Step 2: Confirm scope, do NOT stage or commit**

```bash
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-payroll-frontend
git status --short
```
Do not run `git add` or `git commit`.

---

### Task 7: Frontend (Payroll) — employee list, gross salary, bonuses UI

**Files:**
- Modify: `daf360-payroll-frontend/src/app/modules/employee-config/employee-config.component.ts`
- Modify: `daf360-payroll-frontend/src/app/modules/employee-config/employee-config.component.html`

- [ ] **Step 1: Update the component — imports, injected service, new signals**

Find (confirmed current content, lines 1-11):
```ts
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { EmployeeSelectComponent } from '../../shared/employee-select/employee-select.component';
import { PayrollApiService, BenefitCatalogueDto, PaysDto } from '../../core/payroll-api.service';
import { EmployeeConfigService, EmployeePayrollConfigDto } from './employee-config.service';

// daf360-rh-service's real contract-type domain (EmployeeContract.contractTypeCode /
// ContractTypeConfig.contractTypeCode) — not the narrower 4-value list the payroll
// simulation endpoints' own comments use.
const CONTRACT_TYPES = ['CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT'];
```

Replace:
```ts
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { EmployeeSelectComponent } from '../../shared/employee-select/employee-select.component';
import { PayrollApiService, BenefitCatalogueDto, PaysDto } from '../../core/payroll-api.service';
import { HrProfileService, EmployeePage } from '../../core/hr-profile.service';
import {
  EmployeeConfigService, EmployeePayrollConfigDto, EmployeePayrollBonusDto,
} from './employee-config.service';

// daf360-rh-service's real contract-type domain (EmployeeContract.contractTypeCode /
// ContractTypeConfig.contractTypeCode) — not the narrower 4-value list the payroll
// simulation endpoints' own comments use.
const CONTRACT_TYPES = ['CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT'];

const BONUS_CURRENCIES = ['TND', 'EUR', 'USD', 'EGP', 'SAR', 'AED'];
```

Find (confirmed current content, `imports:` array and class fields, lines 19-44):
```ts
@Component({
  selector: 'app-employee-config',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, EmployeeSelectComponent, TranslatePipe],
  templateUrl: './employee-config.component.html',
})
export class EmployeeConfigComponent implements OnInit {
  private svc = inject(EmployeeConfigService);
  private payrollApi = inject(PayrollApiService);
  private translate = inject(TranslateService);

  readonly contractTypes = CONTRACT_TYPES;

  readonly paysList = signal<PaysDto[]>([]);
  readonly benefits = signal<BenefitCatalogueDto[]>([]);

  readonly selectedUserId = signal<number | null>(null);
  readonly config = signal<EmployeePayrollConfigDto | null>(null);
  readonly reason = signal('');

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);

  ngOnInit(): void {
    this.payrollApi.listPays().subscribe(pays => this.paysList.set(pays));
  }
```

Replace:
```ts
@Component({
  selector: 'app-employee-config',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, EmployeeSelectComponent, TranslatePipe],
  templateUrl: './employee-config.component.html',
})
export class EmployeeConfigComponent implements OnInit {
  private svc = inject(EmployeeConfigService);
  private payrollApi = inject(PayrollApiService);
  private hrService = inject(HrProfileService);
  private translate = inject(TranslateService);

  readonly contractTypes = CONTRACT_TYPES;
  readonly bonusCurrencies = BONUS_CURRENCIES;

  readonly paysList = signal<PaysDto[]>([]);
  readonly benefits = signal<BenefitCatalogueDto[]>([]);

  readonly employeePage = signal<EmployeePage | null>(null);
  readonly employeeListLoading = signal(false);

  readonly selectedUserId = signal<number | null>(null);
  readonly config = signal<EmployeePayrollConfigDto | null>(null);
  readonly reason = signal('');

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly calculatingNet = signal(false);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);

  readonly bonuses = signal<EmployeePayrollBonusDto[]>([]);
  readonly newBonusAmount = signal<number | null>(null);
  readonly newBonusCurrency = signal('TND');
  readonly newBonusMonth = signal(new Date().getMonth() + 1);
  readonly newBonusYear = signal(new Date().getFullYear());
  readonly newBonusLabel = signal('');
  readonly newBonusComment = signal('');
  readonly bonusError = signal<string | null>(null);

  ngOnInit(): void {
    this.payrollApi.listPays().subscribe(pays => this.paysList.set(pays));
    this.loadEmployeeList();
  }

  loadEmployeeList(): void {
    this.employeeListLoading.set(true);
    this.hrService.searchEmployees('').subscribe({
      next: page => { this.employeePage.set(page); this.employeeListLoading.set(false); },
      error: () => this.employeeListLoading.set(false),
    });
  }
```

- [ ] **Step 2: Update `onEmployeeSelected` to also load bonuses, add gross-salary/bonus methods**

Find (confirmed current content, `onEmployeeSelected` through `onSalaryChange`):
```ts
  onEmployeeSelected(userId: number | null): void {
    this.selectedUserId.set(userId);
    this.config.set(null);
    this.benefits.set([]);
    this.error.set(null);
    this.success.set(null);
    if (userId === null) return;
    this.loading.set(true);
    this.svc.get(userId).subscribe({
      next: cfg => {
        this.config.set(cfg);
        this.loading.set(false);
        // Null on a never-configured employee whose country couldn't be auto-resolved — no
        // benefits list to show until the admin picks one via the country select below.
        if (cfg.paysId != null) this.loadBenefitsFor(cfg.paysId);
      },
      error: () => {
        this.loading.set(false);
        this.error.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.LOAD_ERROR'));
      },
    });
  }

  onPaysChange(paysId: number): void {
    const cfg = this.config();
    if (!cfg) return;
    this.config.set({ ...cfg, paysId, selectedBenefitCodes: [] });
    this.loadBenefitsFor(paysId);
  }

  onContractTypeChange(contractType: string): void {
    const cfg = this.config();
    if (cfg) this.config.set({ ...cfg, contractType });
  }

  onSalaryChange(currentNetSalary: number | null): void {
    const cfg = this.config();
    if (cfg) this.config.set({ ...cfg, currentNetSalary });
  }
```

Replace:
```ts
  onEmployeeSelected(userId: number | null): void {
    this.selectedUserId.set(userId);
    this.config.set(null);
    this.benefits.set([]);
    this.bonuses.set([]);
    this.error.set(null);
    this.success.set(null);
    this.bonusError.set(null);
    if (userId === null) return;
    this.loading.set(true);
    this.svc.get(userId).subscribe({
      next: cfg => {
        this.config.set(cfg);
        this.loading.set(false);
        // Null on a never-configured employee whose country couldn't be auto-resolved — no
        // benefits list to show until the admin picks one via the country select below.
        if (cfg.paysId != null) this.loadBenefitsFor(cfg.paysId);
      },
      error: () => {
        this.loading.set(false);
        this.error.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.LOAD_ERROR'));
      },
    });
    this.loadBonuses(userId);
  }

  loadBonuses(userId: number): void {
    this.svc.getBonuses(userId).subscribe({
      next: list => this.bonuses.set(list),
      error: () => {},
    });
  }

  onPaysChange(paysId: number): void {
    const cfg = this.config();
    if (!cfg) return;
    this.config.set({ ...cfg, paysId, selectedBenefitCodes: [] });
    this.loadBenefitsFor(paysId);
  }

  onContractTypeChange(contractType: string): void {
    const cfg = this.config();
    if (cfg) this.config.set({ ...cfg, contractType });
  }

  onGrossSalaryChange(currentGrossSalary: number | null): void {
    const cfg = this.config();
    if (cfg) this.config.set({ ...cfg, currentGrossSalary });
  }

  onSalaryChange(currentNetSalary: number | null): void {
    const cfg = this.config();
    if (cfg) this.config.set({ ...cfg, currentNetSalary });
  }

  calculateNet(): void {
    const userId = this.selectedUserId();
    const cfg = this.config();
    if (userId === null || cfg === null || cfg.currentGrossSalary == null) return;
    this.calculatingNet.set(true);
    this.error.set(null);
    this.svc.calculateNet(userId, cfg.currentGrossSalary).subscribe({
      next: res => {
        this.calculatingNet.set(false);
        this.config.set({ ...cfg, currentNetSalary: res.netInHand });
      },
      error: () => {
        this.calculatingNet.set(false);
        this.error.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.CALCULATE_ERROR'));
      },
    });
  }

  addBonus(): void {
    const userId = this.selectedUserId();
    const amount = this.newBonusAmount();
    const label = this.newBonusLabel().trim();
    this.bonusError.set(null);
    if (userId === null || amount == null || amount <= 0 || label === '') {
      this.bonusError.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.BONUS_VALIDATION_ERROR'));
      return;
    }
    this.svc.createBonus(userId, {
      amount,
      currency: this.newBonusCurrency(),
      periodMonth: this.newBonusMonth(),
      periodYear: this.newBonusYear(),
      label,
      comment: this.newBonusComment().trim() || null,
    }).subscribe({
      next: created => {
        this.bonuses.set([created, ...this.bonuses()]);
        this.newBonusAmount.set(null);
        this.newBonusLabel.set('');
        this.newBonusComment.set('');
      },
      error: () => this.bonusError.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.BONUS_SAVE_ERROR')),
    });
  }

  deleteBonus(bonusId: number): void {
    const userId = this.selectedUserId();
    if (userId === null) return;
    this.svc.deleteBonus(userId, bonusId).subscribe({
      next: () => this.bonuses.set(this.bonuses().filter(b => b.id !== bonusId)),
      error: () => this.bonusError.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.BONUS_DELETE_ERROR')),
    });
  }
```

- [ ] **Step 3: Include `currentGrossSalary` in the save payload**

Find (confirmed current content, inside `save()`):
```ts
    this.svc.upsert(userId, {
      paysId: cfg.paysId,
      contractType: cfg.contractType,
      selectedBenefitCodes: cfg.selectedBenefitCodes,
      currentNetSalary: cfg.currentNetSalary,
      reason: this.reason(),
    }).subscribe({
```

Replace:
```ts
    this.svc.upsert(userId, {
      paysId: cfg.paysId,
      contractType: cfg.contractType,
      selectedBenefitCodes: cfg.selectedBenefitCodes,
      currentGrossSalary: cfg.currentGrossSalary,
      currentNetSalary: cfg.currentNetSalary,
      reason: this.reason(),
    }).subscribe({
```

- [ ] **Step 4: Update the template — employee list, gross salary + Calculer button, bonuses**

Find (confirmed current content, the picker block, lines 4-11):
```html
  <div class="flex flex-col gap-1.5">
    <label class="text-[11px] font-semibold uppercase tracking-wider text-gray-500">
      {{ 'PAYROLL.EMPLOYEE_CONFIG.SELECT_EMPLOYEE' | translate }}
    </label>
    <app-employee-select
      [ngModel]="selectedUserId()"
      (ngModelChange)="onEmployeeSelected($event)" />
  </div>
```

Replace:
```html
  <div class="flex flex-col gap-1.5">
    <label class="text-[11px] font-semibold uppercase tracking-wider text-gray-500">
      {{ 'PAYROLL.EMPLOYEE_CONFIG.SELECT_EMPLOYEE' | translate }}
    </label>
    <app-employee-select
      [ngModel]="selectedUserId()"
      (ngModelChange)="onEmployeeSelected($event)" />
  </div>

  <div class="flex flex-col gap-1.5">
    <label class="text-[11px] font-semibold uppercase tracking-wider text-gray-500">
      {{ 'PAYROLL.EMPLOYEE_CONFIG.EMPLOYEE_LIST' | translate }}
    </label>
    @if (employeeListLoading()) {
      <p class="text-sm text-gray-500">…</p>
    } @else if (employeePage(); as page) {
      <div class="max-h-64 overflow-y-auto rounded-lg border border-gray-200">
        @for (e of page.content; track e.userId) {
          <button type="button"
            class="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-gray-50"
            [class.bg-teal-50]="e.userId === selectedUserId()"
            (click)="onEmployeeSelected(e.userId)">
            <span>{{ e.fullName }}</span>
            @if (e.employeeId) {
              <span class="text-xs text-gray-400">{{ e.employeeId }}</span>
            }
          </button>
        }
      </div>
    }
  </div>
```

Find (confirmed current content, the salary field, lines 64-71):
```html
      <div class="flex flex-col gap-1.5">
        <label class="text-[11px] font-semibold uppercase tracking-wider text-gray-500">
          {{ 'PAYROLL.EMPLOYEE_CONFIG.CURRENT_SALARY' | translate }}
        </label>
        <input type="number" class="rounded-lg border border-gray-300 px-3 py-2"
          [ngModel]="cfg.currentNetSalary"
          (ngModelChange)="onSalaryChange($any($event))" />
      </div>
```

Replace:
```html
      <div class="flex flex-col gap-1.5">
        <label class="text-[11px] font-semibold uppercase tracking-wider text-gray-500">
          {{ 'PAYROLL.EMPLOYEE_CONFIG.GROSS_SALARY' | translate }}
        </label>
        <div class="flex items-center gap-2">
          <input type="number" class="flex-1 rounded-lg border border-gray-300 px-3 py-2"
            [ngModel]="cfg.currentGrossSalary"
            (ngModelChange)="onGrossSalaryChange($any($event))" />
          <button type="button"
            class="shrink-0 rounded-lg bg-gray-200 px-3 py-2 text-sm disabled:opacity-50"
            [disabled]="calculatingNet() || cfg.currentGrossSalary == null"
            (click)="calculateNet()">
            {{ 'PAYROLL.EMPLOYEE_CONFIG.CALCULATE_NET' | translate }}
          </button>
        </div>
      </div>

      <div class="flex flex-col gap-1.5">
        <label class="text-[11px] font-semibold uppercase tracking-wider text-gray-500">
          {{ 'PAYROLL.EMPLOYEE_CONFIG.CURRENT_SALARY' | translate }}
        </label>
        <input type="number" class="rounded-lg border border-gray-300 px-3 py-2"
          [ngModel]="cfg.currentNetSalary"
          (ngModelChange)="onSalaryChange($any($event))" />
      </div>

      <div class="flex flex-col gap-2 rounded-xl border border-gray-200 p-4">
        <label class="text-[11px] font-semibold uppercase tracking-wider text-gray-500">
          {{ 'PAYROLL.EMPLOYEE_CONFIG.BONUSES' | translate }}
        </label>
        @for (b of bonuses(); track b.id) {
          <div class="flex items-center justify-between rounded-lg bg-gray-50 px-3 py-2 text-sm">
            <span>{{ b.periodMonth }}/{{ b.periodYear }} — {{ b.label }} ({{ b.amount }} {{ b.currency }})</span>
            <button type="button" class="text-red-600" (click)="deleteBonus(b.id)">✕</button>
          </div>
        }
        <div class="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-5">
          <input type="number" placeholder="Montant" class="rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
            [ngModel]="newBonusAmount()" (ngModelChange)="newBonusAmount.set($any($event))" />
          <select class="rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
            [ngModel]="newBonusCurrency()" (ngModelChange)="newBonusCurrency.set($event)">
            @for (c of bonusCurrencies; track c) {
              <option [value]="c">{{ c }}</option>
            }
          </select>
          <input type="number" placeholder="Mois" min="1" max="12" class="rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
            [ngModel]="newBonusMonth()" (ngModelChange)="newBonusMonth.set($any($event))" />
          <input type="number" placeholder="Année" class="rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
            [ngModel]="newBonusYear()" (ngModelChange)="newBonusYear.set($any($event))" />
          <input type="text" placeholder="Libellé" class="rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
            [ngModel]="newBonusLabel()" (ngModelChange)="newBonusLabel.set($event)" />
        </div>
        <input type="text" placeholder="Commentaire (optionnel)" class="rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
          [ngModel]="newBonusComment()" (ngModelChange)="newBonusComment.set($event)" />
        <button type="button" class="self-start rounded-lg bg-teal-700 px-3 py-1.5 text-sm text-white"
          (click)="addBonus()">
          {{ 'PAYROLL.EMPLOYEE_CONFIG.ADD_BONUS' | translate }}
        </button>
        @if (bonusError()) {
          <span class="text-sm text-red-700">{{ bonusError() }}</span>
        }
      </div>
```

- [ ] **Step 5: Add the new i18n keys**

Find in `daf360-payroll-frontend/public/i18n/fr.json`, inside the existing `"PAYROLL": { "EMPLOYEE_CONFIG": { ... } }` block, the exact keys already present today (`TITLE`, `SELECT_EMPLOYEE`, `COUNTRY`, `SELECT_COUNTRY`, `CONTRACT_TYPE`, `BENEFITS`, `CURRENT_SALARY`, `REASON`, `SAVE`, `SAVE_SUCCESS`, `SAVE_ERROR`, `LOAD_ERROR`) and add these siblings (the implementer must locate the exact current JSON and insert these keys inside that same object without disturbing the existing ones — this is a JSON insert, not a whole-file replace, since the surrounding file has many unrelated sections):

```json
"EMPLOYEE_LIST": "Liste des employés",
"GROSS_SALARY": "Salaire brut",
"CALCULATE_NET": "Calculer le net",
"CALCULATE_ERROR": "Impossible de calculer le salaire net.",
"BONUSES": "Primes exceptionnelles",
"ADD_BONUS": "Ajouter une prime",
"BONUS_VALIDATION_ERROR": "Montant et libellé sont requis.",
"BONUS_SAVE_ERROR": "Impossible d'enregistrer la prime.",
"BONUS_DELETE_ERROR": "Impossible de supprimer la prime."
```

Repeat the same insertion (English wording) into the matching `"PAYROLL": { "EMPLOYEE_CONFIG": { ... } }` block in `daf360-payroll-frontend/public/i18n/en.json`:
```json
"EMPLOYEE_LIST": "Employee list",
"GROSS_SALARY": "Gross salary",
"CALCULATE_NET": "Calculate net",
"CALCULATE_ERROR": "Could not calculate net salary.",
"BONUSES": "Exceptional bonuses",
"ADD_BONUS": "Add a bonus",
"BONUS_VALIDATION_ERROR": "Amount and label are required.",
"BONUS_SAVE_ERROR": "Could not save the bonus.",
"BONUS_DELETE_ERROR": "Could not delete the bonus."
```

- [ ] **Step 6: Confirm scope, do NOT stage or commit**

```bash
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-payroll-frontend
git status --short
```
Do not run `git add` or `git commit`.

---

### Task 8: Frontend (Payroll) — build verification

**Files:** none (verification only)

- [ ] **Step 1: Real build (matches Docker's actual build command)**

```bash
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-payroll-frontend
npm run build -- --configuration development
```
Expected: build succeeds with no errors. `tsc --noEmit` alone is NOT sufficient verification — this session has hit a real type error that passed `tsc --noEmit` but failed this exact command.

- [ ] **Step 2: Revert the auto-regenerated federation artifact**

```bash
git checkout -- tsconfig.federation.json
```
(Only if `git status` shows it as modified — this file is a build side effect, not a real code change, per this session's established pattern.)

- [ ] **Step 3: Confirm scope, do NOT stage or commit**

```bash
git status --short
```
Do not run `git add` or `git commit`.

---

### Task 9: Frontend (RH) — service layer

**Files:**
- Modify: `daf360-rh-frontend/src/app/modules/profiles/services/remuneration.service.ts`

- [ ] **Step 1: Replace the whole file**

Find (confirmed current content, whole file):
```ts
import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface EmployeePayrollConfigDto {
  profileUserId: number;
  // Null on a never-configured employee whose paysId payroll-service's own cross-service HR
  // lookup couldn't resolve — see RemunerationSectionComponent's own fallback to profile().paysId.
  paysId: number | null;
  contractType: string;
  selectedBenefitCodes: string[];
  currentNetSalary: number | null;
  updatedBy: number | null;
  updatedAt: string | null;
}

export interface UpsertEmployeePayrollConfigRequest {
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentNetSalary: number | null;
  reason: string;
}

export interface BenefitCatalogueDto {
  benefitCode: string;
  benefitLabelFr: string;
  benefitLabelEn: string | null;
}

/**
 * The Rémunération tab's own data source — daf360-payroll-service directly, bypassing this
 * app's own backend, exactly like PayrollSimulationService (candidates module) already does.
 * See docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md.
 */
@Injectable({ providedIn: 'root' })
export class RemunerationService {
  private http = inject(HttpClient);
  private base = `${environment.payrollApiUrl}/api/payroll/employee-configs`;
  private paramSetBase = `${environment.payrollApiUrl}/api/payroll/parameter-sets`;

  get(profileUserId: number): Observable<EmployeePayrollConfigDto> {
    return this.http.get<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`);
  }

  upsert(profileUserId: number, req: UpsertEmployeePayrollConfigRequest): Observable<EmployeePayrollConfigDto> {
    return this.http.put<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`, req);
  }

  /** Benefits catalogue for the employee's own country, same source
   * daf360-payroll-frontend's employee-config screen and simulator already read from.
   * Requires PAYROLL_VIEW_PARAMSET or PAYROLL_RUN_SIMULATION on top of the employee-config
   * permissions — a pairing to grant alongside PAYROLL_VIEW_EMPLOYEE_CONFIG for any role that
   * should see benefits here (see the design doc's permission-rollout note). */
  getBenefits(paysId: number): Observable<{ benefits: BenefitCatalogueDto[] }> {
    return this.http.get<{ benefits: BenefitCatalogueDto[] }>(`${this.paramSetBase}/active`, {
      params: { paysId: paysId.toString() },
    });
  }
}
```

Replace:
```ts
import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

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

export interface BenefitCatalogueDto {
  benefitCode: string;
  benefitLabelFr: string;
  benefitLabelEn: string | null;
}

export interface CalculateNetResponse {
  netInHand: number;
}

export interface EmployeePayrollBonusDto {
  id: number;
  profileUserId: number;
  amount: number;
  currency: string;
  periodMonth: number;
  periodYear: number;
  label: string;
  comment: string | null;
  createdBy: number;
  createdAt: string;
}

export interface CreateEmployeePayrollBonusRequest {
  amount: number;
  currency: string;
  periodMonth: number;
  periodYear: number;
  label: string;
  comment: string | null;
}

/**
 * The Rémunération tab's own data source — daf360-payroll-service directly, bypassing this
 * app's own backend, exactly like PayrollSimulationService (candidates module) already does.
 * See docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md and
 * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md.
 */
@Injectable({ providedIn: 'root' })
export class RemunerationService {
  private http = inject(HttpClient);
  private base = `${environment.payrollApiUrl}/api/payroll/employee-configs`;
  private paramSetBase = `${environment.payrollApiUrl}/api/payroll/parameter-sets`;

  get(profileUserId: number): Observable<EmployeePayrollConfigDto> {
    return this.http.get<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`);
  }

  upsert(profileUserId: number, req: UpsertEmployeePayrollConfigRequest): Observable<EmployeePayrollConfigDto> {
    return this.http.put<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`, req);
  }

  calculateNet(profileUserId: number, grossSalary: number): Observable<CalculateNetResponse> {
    return this.http.post<CalculateNetResponse>(`${this.base}/${profileUserId}/calculate-net`, { grossSalary });
  }

  getBonuses(profileUserId: number): Observable<EmployeePayrollBonusDto[]> {
    return this.http.get<EmployeePayrollBonusDto[]>(`${this.base}/${profileUserId}/bonuses`);
  }

  createBonus(profileUserId: number, req: CreateEmployeePayrollBonusRequest): Observable<EmployeePayrollBonusDto> {
    return this.http.post<EmployeePayrollBonusDto>(`${this.base}/${profileUserId}/bonuses`, req);
  }

  deleteBonus(profileUserId: number, bonusId: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${profileUserId}/bonuses/${bonusId}`);
  }

  /** Benefits catalogue for the employee's own country, same source
   * daf360-payroll-frontend's employee-config screen and simulator already read from.
   * Requires PAYROLL_VIEW_PARAMSET or PAYROLL_RUN_SIMULATION on top of the employee-config
   * permissions — a pairing to grant alongside PAYROLL_VIEW_EMPLOYEE_CONFIG for any role that
   * should see benefits here (see the design doc's permission-rollout note). */
  getBenefits(paysId: number): Observable<{ benefits: BenefitCatalogueDto[] }> {
    return this.http.get<{ benefits: BenefitCatalogueDto[] }>(`${this.paramSetBase}/active`, {
      params: { paysId: paysId.toString() },
    });
  }
}
```

- [ ] **Step 2: Confirm scope, do NOT stage or commit**

```bash
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-rh-frontend
git status --short
```
Do not run `git add` or `git commit`.

---

### Task 10: Frontend (RH) — gross salary + bonuses UI

**Files:**
- Modify: `daf360-rh-frontend/src/app/modules/profiles/detail-sections/remuneration-section.component.ts`

- [ ] **Step 1: Update imports and add signals**

Find (confirmed current content, lines 1-15):
```ts
import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ButtonComponent, FormFieldComponent } from '@khalilrebhiitec/daf360';

import { SectionCardComponent } from '../../../shared/detail/section-card.component';
import { EmployeeProfile } from '../models/profile.model';
import {
  BenefitCatalogueDto, EmployeePayrollConfigDto, RemunerationService,
} from '../services/remuneration.service';

// This app's own real contract-type domain (EmployeeContract.contractTypeCode /
// ContractTypeConfig.contractTypeCode) — not the narrower 4-value list payroll-service's
// own simulation endpoints' comments use.
const CONTRACT_TYPES = ['CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT'];
```

Replace:
```ts
import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ButtonComponent, FormFieldComponent } from '@khalilrebhiitec/daf360';

import { SectionCardComponent } from '../../../shared/detail/section-card.component';
import { EmployeeProfile } from '../models/profile.model';
import {
  BenefitCatalogueDto, EmployeePayrollBonusDto, EmployeePayrollConfigDto, RemunerationService,
} from '../services/remuneration.service';

// This app's own real contract-type domain (EmployeeContract.contractTypeCode /
// ContractTypeConfig.contractTypeCode) — not the narrower 4-value list payroll-service's
// own simulation endpoints' comments use.
const CONTRACT_TYPES = ['CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT'];

const BONUS_CURRENCIES = ['TND', 'EUR', 'USD', 'EGP', 'SAR', 'AED'];
```

- [ ] **Step 2: Add the gross-salary field and bonuses block to the template**

Find (confirmed current content, the salary field inside the template):
```ts
          <daf-form-field
            [value]="cfg.currentNetSalary"
            [options]="{ type: 'number', label: ('PROFILES.FIELDS.REMUNERATION_CURRENT_SALARY' | translate), fullWidth: true }"
            (valueChange)="patchConfig({ currentNetSalary: asNumber($event) })" />
        </div>
```

Replace:
```ts
          <daf-form-field
            [value]="cfg.currentGrossSalary"
            [options]="{ type: 'number', label: ('PROFILES.FIELDS.REMUNERATION_GROSS_SALARY' | translate), fullWidth: true }"
            (valueChange)="patchConfig({ currentGrossSalary: asNumber($event) })" />

          <div class="flex flex-col gap-1.5">
            <label class="text-[10px] font-semibold uppercase tracking-wider text-on-surface-variant">&nbsp;</label>
            <daf-button
              [options]="{
                variant: 'outline', pill: true, iconStart: 'calculate',
                label: ('PROFILES.FIELDS.REMUNERATION_CALCULATE_NET' | translate),
                loading: calculatingNet(), disabled: calculatingNet() || cfg.currentGrossSalary == null
              }"
              (onClick)="calculateNet()" />
          </div>

          <daf-form-field
            [value]="cfg.currentNetSalary"
            [options]="{ type: 'number', label: ('PROFILES.FIELDS.REMUNERATION_CURRENT_SALARY' | translate), fullWidth: true }"
            (valueChange)="patchConfig({ currentNetSalary: asNumber($event) })" />
        </div>

        <div class="mb-4 flex flex-col gap-2 rounded-xl border border-outline-variant p-4">
          <label class="text-[10px] font-semibold uppercase tracking-wider text-on-surface-variant">
            {{ 'PROFILES.FIELDS.REMUNERATION_BONUSES' | translate }}
          </label>
          @for (b of bonuses(); track b.id) {
            <div class="flex items-center justify-between rounded-lg bg-surface-container-low px-3 py-2 text-[13px]">
              <span>{{ b.periodMonth }}/{{ b.periodYear }} — {{ b.label }} ({{ b.amount }} {{ b.currency }})</span>
              <button type="button" class="text-danger" (click)="deleteBonus(b.id)">✕</button>
            </div>
          }
          <div class="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-5">
            <input type="number" placeholder="Montant" class="rounded-lg border border-outline-variant px-2 py-1.5 text-[13px]"
              [ngModel]="newBonusAmount()" (ngModelChange)="newBonusAmount.set($any($event))" />
            <select class="rounded-lg border border-outline-variant px-2 py-1.5 text-[13px]"
              [ngModel]="newBonusCurrency()" (ngModelChange)="newBonusCurrency.set($event)">
              @for (c of bonusCurrencies; track c) {
                <option [value]="c">{{ c }}</option>
              }
            </select>
            <input type="number" placeholder="Mois" min="1" max="12" class="rounded-lg border border-outline-variant px-2 py-1.5 text-[13px]"
              [ngModel]="newBonusMonth()" (ngModelChange)="newBonusMonth.set($any($event))" />
            <input type="number" placeholder="Année" class="rounded-lg border border-outline-variant px-2 py-1.5 text-[13px]"
              [ngModel]="newBonusYear()" (ngModelChange)="newBonusYear.set($any($event))" />
            <input type="text" placeholder="Libellé" class="rounded-lg border border-outline-variant px-2 py-1.5 text-[13px]"
              [ngModel]="newBonusLabel()" (ngModelChange)="newBonusLabel.set($event)" />
          </div>
          <input type="text" placeholder="Commentaire (optionnel)" class="rounded-lg border border-outline-variant px-2 py-1.5 text-[13px]"
            [ngModel]="newBonusComment()" (ngModelChange)="newBonusComment.set($event)" />
          <daf-button
            [options]="{ variant: 'teal', pill: true, iconStart: 'add', label: ('PROFILES.FIELDS.REMUNERATION_ADD_BONUS' | translate) }"
            (onClick)="addBonus()" />
          @if (bonusError()) {
            <span class="text-[12.5px] text-danger">{{ bonusError() }}</span>
          }
        </div>
```

- [ ] **Step 3: Add signals and methods to the class body**

Find (confirmed current content, class field declarations):
```ts
  readonly config   = signal<EmployeePayrollConfigDto | null>(null);
  readonly benefits = signal<BenefitCatalogueDto[]>([]);
  readonly reason   = signal('');
  readonly loading  = signal(true);
  readonly saving   = signal(false);
  readonly error    = signal<string | null>(null);
  readonly success  = signal<string | null>(null);
```

Replace:
```ts
  readonly config   = signal<EmployeePayrollConfigDto | null>(null);
  readonly benefits = signal<BenefitCatalogueDto[]>([]);
  readonly reason   = signal('');
  readonly loading  = signal(true);
  readonly saving   = signal(false);
  readonly calculatingNet = signal(false);
  readonly error    = signal<string | null>(null);
  readonly success  = signal<string | null>(null);

  readonly bonusCurrencies = BONUS_CURRENCIES;
  readonly bonuses = signal<EmployeePayrollBonusDto[]>([]);
  readonly newBonusAmount = signal<number | null>(null);
  readonly newBonusCurrency = signal('TND');
  readonly newBonusMonth = signal(new Date().getMonth() + 1);
  readonly newBonusYear = signal(new Date().getFullYear());
  readonly newBonusLabel = signal('');
  readonly newBonusComment = signal('');
  readonly bonusError = signal<string | null>(null);
```

Find (confirmed current content, end of `ngOnInit`):
```ts
  ngOnInit(): void {
    this.svc.get(this.profile().userId).subscribe({
      next: raw => {
        // A never-configured employee comes back with paysId null when payroll-service's own
        // cross-service HR lookup can't resolve it — but this app already has the employee's
        // real country on the loaded profile, so use that instead of leaving the form
        // half-empty (and unsaveable: the backend's own upsert requires a non-null paysId).
        const cfg = { ...raw, paysId: raw.paysId ?? this.profile().paysId };
        this.config.set(cfg);
        this.loading.set(false);
        this.svc.getBenefits(cfg.paysId).subscribe({
          next: ps => this.benefits.set(ps.benefits ?? []),
          error: () => this.benefits.set([]),
        });
      },
      error: () => {
        this.loading.set(false);
        this.error.set(this.translate.instant('PROFILES.FIELDS.REMUNERATION_LOAD_ERROR'));
      },
    });
  }
```

Replace:
```ts
  ngOnInit(): void {
    this.svc.get(this.profile().userId).subscribe({
      next: raw => {
        // A never-configured employee comes back with paysId null when payroll-service's own
        // cross-service HR lookup can't resolve it — but this app already has the employee's
        // real country on the loaded profile, so use that instead of leaving the form
        // half-empty (and unsaveable: the backend's own upsert requires a non-null paysId).
        const cfg = { ...raw, paysId: raw.paysId ?? this.profile().paysId };
        this.config.set(cfg);
        this.loading.set(false);
        this.svc.getBenefits(cfg.paysId).subscribe({
          next: ps => this.benefits.set(ps.benefits ?? []),
          error: () => this.benefits.set([]),
        });
      },
      error: () => {
        this.loading.set(false);
        this.error.set(this.translate.instant('PROFILES.FIELDS.REMUNERATION_LOAD_ERROR'));
      },
    });
    this.svc.getBonuses(this.profile().userId).subscribe({
      next: list => this.bonuses.set(list),
      error: () => {},
    });
  }

  calculateNet(): void {
    const cfg = this.config();
    if (!cfg || cfg.currentGrossSalary == null) return;
    this.calculatingNet.set(true);
    this.error.set(null);
    this.svc.calculateNet(this.profile().userId, cfg.currentGrossSalary).subscribe({
      next: res => {
        this.calculatingNet.set(false);
        this.config.set({ ...cfg, currentNetSalary: res.netInHand });
      },
      error: () => {
        this.calculatingNet.set(false);
        this.error.set(this.translate.instant('PROFILES.FIELDS.REMUNERATION_CALCULATE_ERROR'));
      },
    });
  }

  addBonus(): void {
    const amount = this.newBonusAmount();
    const label = this.newBonusLabel().trim();
    this.bonusError.set(null);
    if (amount == null || amount <= 0 || label === '') {
      this.bonusError.set(this.translate.instant('PROFILES.FIELDS.REMUNERATION_BONUS_VALIDATION_ERROR'));
      return;
    }
    this.svc.createBonus(this.profile().userId, {
      amount,
      currency: this.newBonusCurrency(),
      periodMonth: this.newBonusMonth(),
      periodYear: this.newBonusYear(),
      label,
      comment: this.newBonusComment().trim() || null,
    }).subscribe({
      next: created => {
        this.bonuses.set([created, ...this.bonuses()]);
        this.newBonusAmount.set(null);
        this.newBonusLabel.set('');
        this.newBonusComment.set('');
      },
      error: () => this.bonusError.set(this.translate.instant('PROFILES.FIELDS.REMUNERATION_BONUS_SAVE_ERROR')),
    });
  }

  deleteBonus(bonusId: number): void {
    this.svc.deleteBonus(this.profile().userId, bonusId).subscribe({
      next: () => this.bonuses.set(this.bonuses().filter(b => b.id !== bonusId)),
      error: () => this.bonusError.set(this.translate.instant('PROFILES.FIELDS.REMUNERATION_BONUS_DELETE_ERROR')),
    });
  }
```

- [ ] **Step 4: Include `currentGrossSalary` in the save payload**

Find (confirmed current content, inside `save()`):
```ts
    this.svc.upsert(this.profile().userId, {
      // Non-null by construction: ngOnInit's `raw.paysId ?? this.profile().paysId` fallback
      // (profile().paysId is itself non-nullable) always resolves this before it ever lands
      // in `config`, unlike the raw EmployeePayrollConfigDto the service can return.
      paysId: cfg.paysId!,
      contractType: cfg.contractType,
      selectedBenefitCodes: cfg.selectedBenefitCodes,
      currentNetSalary: cfg.currentNetSalary,
      reason: this.reason(),
    }).subscribe({
```

Replace:
```ts
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

- [ ] **Step 5: Add the new i18n keys**

Insert into the existing `"PROFILES": { "FIELDS": { ... } }` blocks in `daf360-rh-frontend/public/assets/i18n/fr.json` and `en.json`, alongside the existing `REMUNERATION_*` keys (same insertion pattern as Task 7 Step 5 — locate the exact current JSON, insert without disturbing existing keys):

`fr.json`:
```json
"REMUNERATION_GROSS_SALARY": "Salaire brut",
"REMUNERATION_CALCULATE_NET": "Calculer le net",
"REMUNERATION_CALCULATE_ERROR": "Impossible de calculer le salaire net.",
"REMUNERATION_BONUSES": "Primes exceptionnelles",
"REMUNERATION_ADD_BONUS": "Ajouter une prime",
"REMUNERATION_BONUS_VALIDATION_ERROR": "Montant et libellé sont requis.",
"REMUNERATION_BONUS_SAVE_ERROR": "Impossible d'enregistrer la prime.",
"REMUNERATION_BONUS_DELETE_ERROR": "Impossible de supprimer la prime."
```

`en.json`:
```json
"REMUNERATION_GROSS_SALARY": "Gross salary",
"REMUNERATION_CALCULATE_NET": "Calculate net",
"REMUNERATION_CALCULATE_ERROR": "Could not calculate net salary.",
"REMUNERATION_BONUSES": "Exceptional bonuses",
"REMUNERATION_ADD_BONUS": "Add a bonus",
"REMUNERATION_BONUS_VALIDATION_ERROR": "Amount and label are required.",
"REMUNERATION_BONUS_SAVE_ERROR": "Could not save the bonus.",
"REMUNERATION_BONUS_DELETE_ERROR": "Could not delete the bonus."
```

- [ ] **Step 6: Confirm scope, do NOT stage or commit**

```bash
git status --short
```
Do not run `git add` or `git commit`.

---

### Task 11: Frontend (RH) — build verification

**Files:** none (verification only)

- [ ] **Step 1: Real build**

```bash
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-rh-frontend
npm run build -- --configuration development
```
Expected: build succeeds with no errors.

- [ ] **Step 2: Revert the auto-regenerated federation artifact**

```bash
git checkout -- tsconfig.federation.json
```
(Only if `git status` shows it as modified.)

- [ ] **Step 3: Confirm scope, do NOT stage or commit**

```bash
git status --short
```
Do not run `git add` or `git commit`.

---

### Task 12: Final holistic review

**Files:** none (verification only)

- [ ] **Step 1: Confirm nothing was committed anywhere**

```bash
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-payroll-service && git log -3 --oneline && git status --short
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-payroll-frontend && git log -3 --oneline && git status --short
cd c:/Users/ITEC2/OneDrive/Documents/projects/daf360-rh-frontend && git log -3 --oneline && git status --short
```
Expected: no new commits in any of the three repos beyond the spec-doc commit already made in `daf360-payroll-service` before this plan started; every other touched file shows as plain `M`/`??`, nothing staged.

- [ ] **Step 2: Cross-check spec coverage**

Confirm each design-spec requirement has a corresponding completed task: employee list (Task 7) ✓, gross salary + calculate-net (Tasks 2-3, 7, 10) ✓, primes exceptionnelles CRUD in both apps (Tasks 4, 7, 10) ✓, permission reuse (`PAYROLL_VIEW_EMPLOYEE_CONFIG`/`PAYROLL_MANAGE_EMPLOYEE_CONFIG`, no new permission codes) ✓, no `SimulationResult` persisted by `calculate-net` (Task 2 Step 8) ✓.

- [ ] **Step 3: Report to the user**

Summarize: files touched per repo, migration applied + verified against the real dev DB, backend test results, both frontend build results, and a clear statement that everything remains uncommitted in all three repos pending explicit push authorization.
