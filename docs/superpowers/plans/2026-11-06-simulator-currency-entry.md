# Simulator Currency Entry — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user type an amount into the individual payroll simulator in any currency finance already tracks a rate for (e.g. TND), converting it to the country's working currency before the tax engine runs — without building a second rate-maintenance system.

**Architecture:** Finance's existing `parameter_sets` (`RATE_EUR_<CODE>`) rows stay the single source of truth. A new read endpoint on `daf360-facturation-service` exposes them; `daf360-payroll-service` polls it every 15 minutes (same job, same pattern as its existing entity-currency sync) into a new local cache table `payroll_fx_rates`. The simulator's amount field gains a currency dropdown; on submit, the backend converts through that cache *before* calling `PayrollSimulatorService` (which is never touched), and persists the original currency + amount alongside the converted result for display and faithful "Dupliquer."

**Tech Stack:** Spring Boot (facturation-service, payroll-service), SQL Server via `daf360-sql` migrations, Angular standalone components + signals (payroll-frontend), `@khalilrebhiitec/daf360` shared component library.

**Spec:** `docs/superpowers/specs/2026-11-06-simulator-currency-entry-design.md` (this repo, commit `9d61031`).

---

### Task 1: Database migration — `payroll_fx_rates` table + two new columns on `simulation_results`

**Files:**
- Create: `daf360-sql/db-payroll/migration/V34__simulator_currency_entry.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V34__simulator_currency_entry.sql
-- Supports entering a simulator amount in any currency finance tracks a rate for.
-- payroll_fx_rates is a plain, fully-rebuilt-on-sync cache of finance's RATE_EUR_* values
-- (see FxRateSyncService) -- no history, no manual edits, never the source of truth.

CREATE TABLE dbo.payroll_fx_rates (
    currency_code NVARCHAR(3)    NOT NULL PRIMARY KEY,
    rate_to_eur   DECIMAL(18,6)  NOT NULL,
    synced_at     DATETIME2      NOT NULL
);

ALTER TABLE dbo.simulation_results
    ADD input_currency        NVARCHAR(3)    NULL,
        input_amount_original DECIMAL(18,4)  NULL;
GO
```

- [ ] **Step 2: Apply it to local dev**

Run from `daf360-sql`:
```powershell
.\apply.ps1 -Apply -Db payroll -Yes
```
Expected: `V34__simulator_currency_entry.sql` reported `APPLIED`. If `-Status` beforehand shows anything else already `PENDING` ahead of V34 that isn't part of this plan, stop and tell the user before proceeding — do not reorder or skip it.

- [ ] **Step 3: Verify**

```powershell
docker exec timesheet-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -d DAF360_PAYROLL -Q "SET NOCOUNT ON; SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'simulation_results' AND COLUMN_NAME LIKE 'input_%';"
```
Expected: `input_net`, `input_currency`, `input_amount_original` all listed. (Password comes from `..\.env.dev`'s `DB_PASSWORD` — read it into a variable first rather than typing it inline, per this repo's existing convention, e.g. `$pw = (Get-Content ..\.env.dev | Where-Object { $_ -match '^DB_PASSWORD=' }) -replace '^DB_PASSWORD=','' -replace '"','' ` then pass `-e "SQLCMDPASSWORD=$pw"` to `docker exec`.)

- [ ] **Step 4: Commit**

```bash
cd daf360-sql
git add db-payroll/migration/V34__simulator_currency_entry.sql
git commit -m "feat(payroll): add payroll_fx_rates cache + simulator input-currency columns"
```

---

### Task 2: Facturation — expose FX rates for sync

**Files:**
- Modify: `daf360-facturation-service/src/main/java/com/daf360/facturation/modules/config/repository/ParameterSetRepository.java`
- Modify: `daf360-facturation-service/src/main/java/com/daf360/facturation/modules/ref/controller/RefController.java`
- Test: `daf360-facturation-service/src/test/java/com/daf360/facturation/modules/ref/controller/RefControllerTest.java`

- [ ] **Step 1: Add the repository query**

In `ParameterSetRepository.java`, add alongside the existing two methods:

```java
    List<ParameterSet> findByParamKeyStartingWith(String prefix);
```

Add `import java.util.List;` at the top if not already present.

- [ ] **Step 2: Add the sync endpoint**

In `RefController.java`, add this record and method near `EntityForSyncDto`/`entitiesForSync()` (same file, same class — reuse the existing injected fields, no new constructor dependency needed since `ParameterSetRepository` already exists as `rateCompRepo`'s sibling... actually it is NOT currently injected into this controller, so add it):

Add to the constructor-injected fields list (near the top of the class, alongside `rateCompRepo`):
```java
    private final ParameterSetRepository parameterSetRepo;
```
And add it to the `@RequiredArgsConstructor`-generated constructor — since this class uses `@RequiredArgsConstructor`, no manual constructor edit is needed; just add the field and Lombok generates the parameter.

Then add the import:
```java
import com.daf360.facturation.modules.config.repository.ParameterSetRepository;
```

Then add, right after `entitiesForSync()`:
```java
    /** Shape for GET /fx-rates — currency code with its RATE_EUR_<CODE> rate. */
    public record FxRateForSyncDto(String currencyCode, java.math.BigDecimal rateToEur) {}

    /**
     * Internal service-to-service sync endpoint — payroll's FxRateSyncService polls this every
     * 15 minutes, the same pattern as entitiesForSync() above. permitAll in SecurityConfig:
     * trusted by network boundary, not reachable by any end-user action. Source of truth stays
     * finance's parameter_sets; this is a read-only projection of every RATE_EUR_<CODE> row.
     */
    @GetMapping("/fx-rates")
    public ResponseEntity<List<FxRateForSyncDto>> fxRatesForSync() {
        List<FxRateForSyncDto> result = parameterSetRepo.findByParamKeyStartingWith("RATE_EUR_").stream()
                .map(p -> new FxRateForSyncDto(
                        p.getParamKey().substring("RATE_EUR_".length()),
                        new java.math.BigDecimal(p.getParamValue())))
                .toList();
        return ResponseEntity.ok(result);
    }
```

- [ ] **Step 3: Permit this path without auth, same as `entities-for-sync`**

In `daf360-facturation-service/src/main/java/com/daf360/facturation/config/SecurityConfig.java`, find:

```java
                .requestMatchers(HttpMethod.GET, "/api/fact/ref/entities-for-sync").permitAll()
```

Add right after it:

```java
                .requestMatchers(HttpMethod.GET, "/api/fact/ref/fx-rates").permitAll()
```

- [ ] **Step 4: Write the test**

In `RefControllerTest.java`, add `@Mock ParameterSetRepository parameterSetRepo;` to the mocks list, then:

```java
    @Test
    void fxRatesForSync_returnsEveryRateEurRow_withCurrencyCodeExtracted() {
        ParameterSet tndRate = ParameterSet.builder()
                .paramKey("RATE_EUR_TND").paramValue("0.300000").build();
        ParameterSet usdRate = ParameterSet.builder()
                .paramKey("RATE_EUR_USD").paramValue("0.920000").build();
        when(parameterSetRepo.findByParamKeyStartingWith("RATE_EUR_"))
                .thenReturn(List.of(tndRate, usdRate));

        var result = controller.fxRatesForSync();

        assertThat(result.getBody()).hasSize(2);
        assertThat(result.getBody()).extracting(RefController.FxRateForSyncDto::currencyCode)
                .containsExactlyInAnyOrder("TND", "USD");
        assertThat(result.getBody()).filteredOn(r -> r.currencyCode().equals("TND"))
                .first().extracting(RefController.FxRateForSyncDto::rateToEur)
                .isEqualTo(new java.math.BigDecimal("0.300000"));
    }
```

Add the needed imports to the test file: `com.daf360.facturation.modules.config.domain.ParameterSet` and `com.daf360.facturation.modules.config.repository.ParameterSetRepository`.

- [ ] **Step 5: Run the test**

```bash
cd daf360-facturation-service
mvn test -Dtest=RefControllerTest
```
Expected: `Tests run: 6, Failures: 0, Errors: 0` (5 existing + 1 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/daf360/facturation/modules/config/repository/ParameterSetRepository.java \
        src/main/java/com/daf360/facturation/modules/ref/controller/RefController.java \
        src/test/java/com/daf360/facturation/modules/ref/controller/RefControllerTest.java
git commit -m "feat(ref): expose FX rates via /api/fact/ref/fx-rates for payroll's sync"
```

---

### Task 3: Payroll — `payroll_fx_rates` entity + repository

**Files:**
- Create: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/ref/entity/PayrollFxRate.java`
- Create: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/ref/repository/PayrollFxRateRepository.java`

- [ ] **Step 1: Write the entity**

```java
package com.daf360.payroll.modules.ref.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Local, fully-rebuilt-on-sync cache of finance's RATE_EUR_<CODE> rows (see
 *  ProfileSyncService.syncFxRates). Never edited by hand -- finance's parameter_sets
 *  stays the one place anyone maintains a rate. */
@Entity
@Table(name = "payroll_fx_rates")
@Getter @Setter
public class PayrollFxRate {

    @Id
    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "rate_to_eur", nullable = false)
    private BigDecimal rateToEur;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt = LocalDateTime.now();
}
```

- [ ] **Step 2: Write the repository**

```java
package com.daf360.payroll.modules.ref.repository;

import com.daf360.payroll.modules.ref.entity.PayrollFxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayrollFxRateRepository extends JpaRepository<PayrollFxRate, String> {

    Optional<PayrollFxRate> findByCurrencyCode(String currencyCode);
}
```

- [ ] **Step 3: Commit**

```bash
cd daf360-payroll-service
git add src/main/java/com/daf360/payroll/modules/ref/entity/PayrollFxRate.java \
        src/main/java/com/daf360/payroll/modules/ref/repository/PayrollFxRateRepository.java
git commit -m "feat(ref): add PayrollFxRate entity/repository for the currency-entry cache"
```

---

### Task 4: Payroll — sync job pulling rates from finance

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/ref/service/ProfileSyncService.java`
- Test: `daf360-payroll-service/src/test/java/com/daf360/payroll/modules/ref/service/ProfileSyncServiceTest.java`

- [ ] **Step 1: Read the current full file**

Read `ProfileSyncService.java` in full before editing — this plan was written against the version with `syncEntityCurrencies()` at lines 164-214 and the constructor at lines 50-60; confirm nothing shifted since.

- [ ] **Step 2: Add the dependency and wire it into `sync()`**

Change the constructor's field list and body to add `PayrollFxRateRepository`:

```java
    private final RestTemplate restTemplate;
    private final PaysRefRepository paysRefRepository;
    private final UsersRefRepository usersRefRepository;
    private final AppProperties appProperties;
    private final PayrollCountryRepository payrollCountryRepository;
    private final PayrollFxRateRepository fxRateRepository;

    @Value("${app.rh-service-url:http://localhost:8888}")
    private String rhServiceUrl;

    @Value("${app.facturation-service-url:http://localhost:8892}")
    private String facturationServiceUrl;

    public ProfileSyncService(RestTemplate restTemplate,
                              PaysRefRepository paysRefRepository,
                              UsersRefRepository usersRefRepository,
                              AppProperties appProperties,
                              PayrollCountryRepository payrollCountryRepository,
                              PayrollFxRateRepository fxRateRepository) {
        this.restTemplate = restTemplate;
        this.paysRefRepository = paysRefRepository;
        this.usersRefRepository = usersRefRepository;
        this.appProperties = appProperties;
        this.payrollCountryRepository = payrollCountryRepository;
        this.fxRateRepository = fxRateRepository;
    }

    @Scheduled(fixedDelay = 15 * 60 * 1_000, initialDelay = 5_000)
    public void sync() {
        syncPays();
        syncUsers();
        syncEntityCurrencies();
        syncFxRates();
    }
```

Add the import: `import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;` and `import com.daf360.payroll.modules.ref.entity.PayrollFxRate;` and `import java.time.LocalDateTime;`.

- [ ] **Step 3: Add `syncFxRates()`**

Add this method at the end of the class, right after `syncEntityCurrencies()`:

```java
    /**
     * Polls finance's fx-rates endpoint (its admin-maintained RATE_EUR_<CODE> rows) and
     * rebuilds payroll_fx_rates from it wholesale -- this cache is never edited by hand, so
     * there is nothing to merge: every synced row simply overwrites its prior value.
     */
    void syncFxRates() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    facturationServiceUrl + "/api/fact/ref/fx-rates",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});

            List<Map<String, Object>> rates = response.getBody();
            if (rates == null || rates.isEmpty()) {
                log.warn("fx-rates sync: {} returned no rate — payroll_fx_rates left untouched",
                        facturationServiceUrl + "/api/fact/ref/fx-rates");
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            List<PayrollFxRate> entities = rates.stream().map(m -> {
                PayrollFxRate rate = new PayrollFxRate();
                rate.setCurrencyCode((String) m.get("currencyCode"));
                rate.setRateToEur(new java.math.BigDecimal(m.get("rateToEur").toString()));
                rate.setSyncedAt(now);
                return rate;
            }).toList();

            fxRateRepository.saveAll(entities);
            log.info("Synced {} FX rates from facturation-service", entities.size());
        } catch (Exception e) {
            log.warn("fx-rates sync failed against {}: {}",
                    facturationServiceUrl + "/api/fact/ref/fx-rates", e.getMessage());
        }
    }
```

- [ ] **Step 4: Update the existing test file's constructor calls**

`ProfileSyncServiceTest.java` has exactly one call site constructing `ProfileSyncService` directly, in `setUp()`:

```java
    @Mock RestTemplate restTemplate;
    @Mock PaysRefRepository paysRefRepository;
    @Mock UsersRefRepository usersRefRepository;
    @Mock AppProperties appProperties;
    @Mock PayrollCountryRepository payrollCountryRepository;

    ProfileSyncService service;

    @BeforeEach
    void setUp() {
        service = new ProfileSyncService(restTemplate, paysRefRepository, usersRefRepository,
                appProperties, payrollCountryRepository);
        ReflectionTestUtils.setField(service, "facturationServiceUrl", FACT_URL);
    }
```

Replace with:

```java
    @Mock RestTemplate restTemplate;
    @Mock PaysRefRepository paysRefRepository;
    @Mock UsersRefRepository usersRefRepository;
    @Mock AppProperties appProperties;
    @Mock PayrollCountryRepository payrollCountryRepository;
    @Mock PayrollFxRateRepository fxRateRepository;

    ProfileSyncService service;

    @BeforeEach
    void setUp() {
        service = new ProfileSyncService(restTemplate, paysRefRepository, usersRefRepository,
                appProperties, payrollCountryRepository, fxRateRepository);
        ReflectionTestUtils.setField(service, "facturationServiceUrl", FACT_URL);
    }
```

Add the import: `import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;` and `import com.daf360.payroll.modules.ref.entity.PayrollFxRate;`.

- [ ] **Step 5: Add tests for the new method**

Add, after the existing `ENTITIES_FOR_SYNC_URL` constant declaration, a sibling constant:

```java
    private static final String FX_RATES_URL = FACT_URL + "/api/fact/ref/fx-rates";
```

Then add these three tests, matching this file's exact existing idioms (`eq(...)`/`isNull()`/`any(ParameterizedTypeReference.class)`, AssertJ's `assertThat`, `ArgumentCaptor`):

```java
    @Test
    void syncFxRates_savesEveryRateReturnedByFacturation() {
        List<Map<String, Object>> rates = List.of(
                Map.of("currencyCode", "TND", "rateToEur", "0.300000"),
                Map.of("currencyCode", "USD", "rateToEur", "0.920000"));
        when(restTemplate.exchange(eq(FX_RATES_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(rates));

        service.syncFxRates();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PayrollFxRate>> captor = ArgumentCaptor.forClass(List.class);
        verify(fxRateRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).anySatisfy(r -> {
            assertThat(r.getCurrencyCode()).isEqualTo("TND");
            assertThat(r.getRateToEur()).isEqualByComparingTo("0.300000");
        });
    }

    @Test
    void syncFxRates_emptyResponse_leavesCacheUntouched() {
        when(restTemplate.exchange(eq(FX_RATES_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(List.of()));

        service.syncFxRates();

        verify(fxRateRepository, never()).saveAll(any());
    }

    @Test
    void syncFxRates_restCallFails_isSwallowed() {
        when(restTemplate.exchange(eq(FX_RATES_URL),
                eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatCode(() -> service.syncFxRates()).doesNotThrowAnyException();

        verify(fxRateRepository, never()).saveAll(any());
    }
```

- [ ] **Step 6: Run the tests**

```bash
cd daf360-payroll-service
mvn test -Dtest=ProfileSyncServiceTest
```
Expected: `Tests run: 11, Failures: 0, Errors: 0` (8 existing + 3 new).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/ref/service/ProfileSyncService.java \
        src/test/java/com/daf360/payroll/modules/ref/service/ProfileSyncServiceTest.java
git commit -m "feat(ref): sync FX rates from finance into payroll_fx_rates every 15 minutes"
```

---

### Task 5: Payroll — conversion service + 422 on unknown currency

**Files:**
- Create: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/ref/service/FxEntryConversionException.java`
- Create: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/ref/service/FxEntryConversionService.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/web/GlobalExceptionHandler.java`
- Test: `daf360-payroll-service/src/test/java/com/daf360/payroll/modules/ref/service/FxEntryConversionServiceTest.java`

- [ ] **Step 1: Write the exception**

```java
package com.daf360.payroll.modules.ref.service;

/** Thrown when a simulator request names a currency payroll_fx_rates has no cached rate for
 *  (sync hasn't run yet, or finance has nothing configured for it). Never silently falls back
 *  to a 1:1 rate -- a wrong silent rate is exactly what this feature exists to prevent. */
public class FxEntryConversionException extends RuntimeException {
    public FxEntryConversionException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Write the conversion service**

```java
package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts a simulator-entered amount from any currency payroll_fx_rates has a cached rate for
 * into the country's working currency, composing through EUR (every cached rate is relative to
 * EUR, mirroring finance's own RATE_EUR_<CODE> convention). Used ONLY at simulation-request
 * entry, before PayrollSimulatorService is called -- the engine itself never sees a currency
 * other than the country's working one.
 */
@Service
public class FxEntryConversionService {

    private final PayrollFxRateRepository fxRateRepo;

    public FxEntryConversionService(PayrollFxRateRepository fxRateRepo) {
        this.fxRateRepo = fxRateRepo;
    }

    /**
     * @param amount        the amount as entered, in {@code fromCurrency}
     * @param fromCurrency  the currency the user actually typed the amount in
     * @param toCurrency    the country's working currency (what the engine must receive)
     * @throws FxEntryConversionException if either currency has no cached rate
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (fromCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) return amount;

        BigDecimal fromRate = rateToEur(fromCurrency);
        BigDecimal toRate = rateToEur(toCurrency);
        return amount.multiply(fromRate).divide(toRate, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal rateToEur(String currency) {
        if ("EUR".equalsIgnoreCase(currency)) return BigDecimal.ONE;
        return fxRateRepo.findByCurrencyCode(currency.toUpperCase())
                .map(r -> r.getRateToEur())
                .orElseThrow(() -> new FxEntryConversionException(
                        "Aucun taux de change disponible pour '" + currency
                                + "'. Le synchronisateur de taux n'a peut-être pas encore tourné."));
    }
}
```

- [ ] **Step 3: Add the exception handler**

In `GlobalExceptionHandler.java`, add this method right after `handleConvergence`, and add the import `import com.daf360.payroll.modules.ref.service.FxEntryConversionException;`:

```java
    @ExceptionHandler(FxEntryConversionException.class)
    public ProblemDetail handleFxEntryConversion(FxEntryConversionException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(422);
        pd.setDetail(ex.getMessage());
        return pd;
    }
```

- [ ] **Step 4: Write the tests**

```java
package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.modules.ref.entity.PayrollFxRate;
import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxEntryConversionServiceTest {

    @Mock PayrollFxRateRepository fxRateRepo;

    private FxEntryConversionService service;

    @Test
    void convert_sameCurrency_returnsAmountUnchanged() {
        service = new FxEntryConversionService(fxRateRepo);

        BigDecimal result = service.convert(new BigDecimal("198.00"), "EUR", "EUR");

        assertEquals(new BigDecimal("198.00"), result);
    }

    @Test
    void convert_tndToEur_appliesRateDirectly() {
        service = new FxEntryConversionService(fxRateRepo);
        PayrollFxRate tnd = new PayrollFxRate();
        tnd.setCurrencyCode("TND");
        tnd.setRateToEur(new BigDecimal("0.300000"));
        when(fxRateRepo.findByCurrencyCode("TND")).thenReturn(Optional.of(tnd));

        // 198 TND x 0.300000 EUR/TND / 1 (EUR working currency) = 59.4000
        BigDecimal result = service.convert(new BigDecimal("198"), "TND", "EUR");

        assertEquals(0, new BigDecimal("59.4000").compareTo(result));
    }

    @Test
    void convert_tndToUsd_pivotsThroughEur() {
        service = new FxEntryConversionService(fxRateRepo);
        PayrollFxRate tnd = new PayrollFxRate();
        tnd.setCurrencyCode("TND");
        tnd.setRateToEur(new BigDecimal("0.300000"));
        PayrollFxRate usd = new PayrollFxRate();
        usd.setCurrencyCode("USD");
        usd.setRateToEur(new BigDecimal("0.920000"));
        when(fxRateRepo.findByCurrencyCode("TND")).thenReturn(Optional.of(tnd));
        when(fxRateRepo.findByCurrencyCode("USD")).thenReturn(Optional.of(usd));

        // 100 TND -> 30 EUR -> 30 / 0.92 = 32.6087 USD
        BigDecimal result = service.convert(new BigDecimal("100"), "TND", "USD");

        assertEquals(0, new BigDecimal("32.6087").compareTo(result));
    }

    @Test
    void convert_unknownCurrency_throwsFxEntryConversionException() {
        service = new FxEntryConversionService(fxRateRepo);
        when(fxRateRepo.findByCurrencyCode("XYZ")).thenReturn(Optional.empty());

        assertThrows(FxEntryConversionException.class,
                () -> service.convert(new BigDecimal("100"), "XYZ", "EUR"));
    }

    @Test
    void convert_nullFromCurrency_returnsAmountUnchanged() {
        service = new FxEntryConversionService(fxRateRepo);

        BigDecimal result = service.convert(new BigDecimal("415.40"), null, "EUR");

        assertEquals(new BigDecimal("415.40"), result);
    }
}
```

- [ ] **Step 5: Run the tests**

```bash
mvn test -Dtest=FxEntryConversionServiceTest
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/ref/service/FxEntryConversionException.java \
        src/main/java/com/daf360/payroll/modules/ref/service/FxEntryConversionService.java \
        src/main/java/com/daf360/payroll/web/GlobalExceptionHandler.java \
        src/test/java/com/daf360/payroll/modules/ref/service/FxEntryConversionServiceTest.java
git commit -m "feat(ref): convert simulator entry currency before the engine runs, 422 if unrated"
```

---

### Task 6: Payroll — `SimulationRequest` gains `inputCurrency`

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/simulation/dto/SimulationRequest.java`

- [ ] **Step 1: Add the field**

Current file (for reference, do not re-type unchanged lines — this is the exact current content):

```java
public record SimulationRequest(
        @NotNull Long paysId,
        // NET_TO_BRUT (default): required. BRUT_TO_NET: ignored. Validated in service.
        @Positive BigDecimal inputNet,
        Long profileUserId,
        String contractType,              // CDI|CDD|STAGE|CIVP  (defaults to CDI)
        Integer joursTravailes,           // working days for FIXE_JOURNALIER rubriques (defaults to 22)
        List<String> selectedBenefitCodes,// null = apply all active benefits; empty = no benefits
        Boolean isMarried,                 // family IRPP-base deduction; null = not married
        Integer children,                  // dependent children count; null = 0
        String candidateLabel,            // free-text label saved in result (for PDF report)
        String poste,                     // job title saved in result
        String grade,                     // grade saved in result
        String discipline,                // discipline saved in result
        // ── simulation direction ──────────────────────────────────────────────
        SimulationMode mode,              // null → NET_TO_BRUT (backward-compatible default)
        @Positive BigDecimal inputGross   // BRUT_TO_NET: required. NET_TO_BRUT: ignored.
) {}
```

Add `inputCurrency` as the last field:

```java
public record SimulationRequest(
        @NotNull Long paysId,
        // NET_TO_BRUT (default): required. BRUT_TO_NET: ignored. Validated in service.
        @Positive BigDecimal inputNet,
        Long profileUserId,
        String contractType,              // CDI|CDD|STAGE|CIVP  (defaults to CDI)
        Integer joursTravailes,           // working days for FIXE_JOURNALIER rubriques (defaults to 22)
        List<String> selectedBenefitCodes,// null = apply all active benefits; empty = no benefits
        Boolean isMarried,                 // family IRPP-base deduction; null = not married
        Integer children,                  // dependent children count; null = 0
        String candidateLabel,            // free-text label saved in result (for PDF report)
        String poste,                     // job title saved in result
        String grade,                     // grade saved in result
        String discipline,                // discipline saved in result
        // ── simulation direction ──────────────────────────────────────────────
        SimulationMode mode,              // null → NET_TO_BRUT (backward-compatible default)
        @Positive BigDecimal inputGross,  // BRUT_TO_NET: required. NET_TO_BRUT: ignored.
        // null/omitted = the country's working currency (today's behavior, zero change for
        // existing callers). When set and different, inputNet/inputGross are converted to the
        // working currency before the engine runs -- see IndividualSimulationService.
        String inputCurrency
) {}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/simulation/dto/SimulationRequest.java
git commit -m "feat(simulation): add optional inputCurrency to SimulationRequest"
```

---

### Task 7: Payroll — persist the original currency/amount on the result

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/simulation/entity/SimulationResult.java`
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/simulation/dto/SimulationResultDto.java`

- [ ] **Step 1: Add the two columns to the entity**

In `SimulationResult.java`, add right after the `inputNet` field:

```java
    @Column(name = "input_net", nullable = false)
    private BigDecimal inputNet;

    /** The currency actually typed into the form, when different from the country's working
     *  currency (local_currency). Null when the user never touched the currency selector --
     *  the common case, and existing rows before this feature are null here by migration. */
    @Column(name = "input_currency", length = 3)
    private String inputCurrency;

    /** The amount as typed, in inputCurrency -- before conversion to the working currency.
     *  Null exactly when inputCurrency is null. */
    @Column(name = "input_amount_original")
    private BigDecimal inputAmountOriginal;
```

- [ ] **Step 2: Add the two fields to the DTO**

In `SimulationResultDto.java`, add right after `inputNet` in the record's parameter list:

```java
        BigDecimal inputNet,          // S1 net versé
        String inputCurrency,         // currency actually typed, null = working currency
        BigDecimal inputAmountOriginal, // amount as typed, in inputCurrency; null = same as inputNet
        BigDecimal netTaxable,        // S2 net imposable (IRPP base)
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/simulation/entity/SimulationResult.java \
        src/main/java/com/daf360/payroll/modules/simulation/dto/SimulationResultDto.java
git commit -m "feat(simulation): add inputCurrency/inputAmountOriginal to SimulationResult"
```

---

### Task 8: Payroll — wire the conversion into `IndividualSimulationService`

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/simulation/service/IndividualSimulationService.java`

- [ ] **Step 1: Inject the new service**

Change the constructor to add `FxEntryConversionService`:

```java
    private final ParameterSetService paramSetService;
    private final PayrollSimulatorService simulatorService;
    private final FxSnapshotService fxService;
    private final FxEntryConversionService fxEntryService;
    private final SimulationResultRepository resultRepo;
    private final ObjectMapper objectMapper;
    private final HrEmployeeClient hrClient;

    public IndividualSimulationService(ParameterSetService paramSetService,
                                       PayrollSimulatorService simulatorService,
                                       FxSnapshotService fxService,
                                       FxEntryConversionService fxEntryService,
                                       SimulationResultRepository resultRepo,
                                       ObjectMapper objectMapper,
                                       HrEmployeeClient hrClient) {
        this.paramSetService = paramSetService;
        this.simulatorService = simulatorService;
        this.fxService = fxService;
        this.fxEntryService = fxEntryService;
        this.resultRepo = resultRepo;
        this.objectMapper = objectMapper;
        this.hrClient = hrClient;
    }
```

Add the import: `import com.daf360.payroll.modules.ref.service.FxEntryConversionService;`.

- [ ] **Step 2: Resolve the FX snapshot earlier, and convert before the mode branch**

Replace this block:

```java
        ParameterSet ps = paramSetService.loadActiveEntity(req.paysId());
        List<SocialChargeRate> rates = paramSetService.loadRates(ps.getId());
        List<BenefitCatalogue> allBenefits = paramSetService.loadBenefits(ps.getId());
        List<PayrollRubrique> rubriques = paramSetService.loadRubriques(ps.getId());

        // Filter benefits by selectedBenefitCodes (null = all; empty list = none)
        List<BenefitCatalogue> benefits = (req.selectedBenefitCodes() == null)
                ? allBenefits
                : allBenefits.stream()
                        .filter(b -> req.selectedBenefitCodes().contains(b.getBenefitCode()))
                        .toList();

        // ── Mode branch ──────────────────────────────────────────────────────────
        SimulationMode mode = req.mode() != null ? req.mode() : SimulationMode.NET_TO_BRUT;

        PayrollSimulatorService.PayrollResult result;
        if (mode == SimulationMode.BRUT_TO_NET) {
            if (req.inputGross() == null || req.inputGross().compareTo(java.math.BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException(
                        "Le champ inputGross est requis et doit être positif en mode BRUT_TO_NET.");
            result = simulatorService.computeFromGross(
                    req.inputGross(), ps, rates, benefits, rubriques, contractType, joursTravailes,
                    req.isMarried(), req.children());
        } else {
            if (req.inputNet() == null || req.inputNet().compareTo(java.math.BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException(
                        "Le champ inputNet est requis et doit être positif en mode NET_TO_BRUT.");
            result = simulatorService.computeFromNet(
                    req.inputNet(), ps, rates, benefits, rubriques, contractType, joursTravailes,
                    req.isMarried(), req.children());
        }
```

with:

```java
        ParameterSet ps = paramSetService.loadActiveEntity(req.paysId());
        List<SocialChargeRate> rates = paramSetService.loadRates(ps.getId());
        List<BenefitCatalogue> allBenefits = paramSetService.loadBenefits(ps.getId());
        List<PayrollRubrique> rubriques = paramSetService.loadRubriques(ps.getId());

        // Filter benefits by selectedBenefitCodes (null = all; empty list = none)
        List<BenefitCatalogue> benefits = (req.selectedBenefitCodes() == null)
                ? allBenefits
                : allBenefits.stream()
                        .filter(b -> req.selectedBenefitCodes().contains(b.getBenefitCode()))
                        .toList();

        // Resolved early (not just before persisting, as before): the working currency is
        // needed now, to know whether/how to convert a non-working-currency entry.
        FxSnapshotService.FxSnapshot fx = fxService.resolve(req.paysId());

        // ── Currency entry conversion ────────────────────────────────────────────
        // null/same-as-working inputCurrency: no-op, req.inputNet()/inputGross() pass through
        // unchanged -- today's behavior, exactly.
        java.math.BigDecimal rawInputNet = req.inputNet();
        java.math.BigDecimal rawInputGross = req.inputGross();
        boolean currencyConverted = req.inputCurrency() != null
                && !req.inputCurrency().equalsIgnoreCase(fx.currency());
        java.math.BigDecimal convertedInputNet = rawInputNet == null ? null
                : fxEntryService.convert(rawInputNet, req.inputCurrency(), fx.currency());
        java.math.BigDecimal convertedInputGross = rawInputGross == null ? null
                : fxEntryService.convert(rawInputGross, req.inputCurrency(), fx.currency());

        // ── Mode branch ──────────────────────────────────────────────────────────
        SimulationMode mode = req.mode() != null ? req.mode() : SimulationMode.NET_TO_BRUT;

        PayrollSimulatorService.PayrollResult result;
        if (mode == SimulationMode.BRUT_TO_NET) {
            if (convertedInputGross == null || convertedInputGross.compareTo(java.math.BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException(
                        "Le champ inputGross est requis et doit être positif en mode BRUT_TO_NET.");
            result = simulatorService.computeFromGross(
                    convertedInputGross, ps, rates, benefits, rubriques, contractType, joursTravailes,
                    req.isMarried(), req.children());
        } else {
            if (convertedInputNet == null || convertedInputNet.compareTo(java.math.BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException(
                        "Le champ inputNet est requis et doit être positif en mode NET_TO_BRUT.");
            result = simulatorService.computeFromNet(
                    convertedInputNet, ps, rates, benefits, rubriques, contractType, joursTravailes,
                    req.isMarried(), req.children());
        }
```

- [ ] **Step 3: Remove the now-duplicate FX snapshot resolution further down, and persist the original entry**

Find this block (further down, inside the same method):

```java
        entity.setLoadedCost(result.loadedCost());
        // Resolve the FX snapshot ONCE for this request — avoids re-querying payroll_countries
        // per field.
        FxSnapshotService.FxSnapshot fx = fxService.resolve(req.paysId());
        entity.setLocalCurrency(fx.currency());
```

Replace with (the snapshot is now resolved earlier; delete the re-declaration, keep using `fx`):

```java
        entity.setLoadedCost(result.loadedCost());
        entity.setLocalCurrency(fx.currency());
```

Then, right after `entity.setInputNet(netInHand);`, add:

```java
        entity.setInputNet(netInHand);
        entity.setInputCurrency(currencyConverted ? req.inputCurrency().toUpperCase() : null);
        entity.setInputAmountOriginal(currencyConverted
                ? (mode == SimulationMode.BRUT_TO_NET ? rawInputGross : rawInputNet)
                : null);
```

- [ ] **Step 4: Update `toDto()`**

Find:

```java
        return new SimulationResultDto(
                e.getId(), e.getPaysId(), e.getProfileUserId(), e.getParameterSetId(),
                e.getSimulationType(), e.getContractType(),
                e.getInputNet(), e.getNetTaxable(), e.getTaxableBase(),
```

Replace with:

```java
        return new SimulationResultDto(
                e.getId(), e.getPaysId(), e.getProfileUserId(), e.getParameterSetId(),
                e.getSimulationType(), e.getContractType(),
                e.getInputNet(), e.getInputCurrency(), e.getInputAmountOriginal(),
                e.getNetTaxable(), e.getTaxableBase(),
```

- [ ] **Step 5: Check and update `CohortSimulationService` / `CohortAggregateService` / `SimulationHistoryService` if they construct `SimulationResultDto` directly**

Search for other callers that build a `SimulationResultDto` positionally (not via this class's `toDto()`):

```bash
grep -rn "new SimulationResultDto(" src/main/java
```

If any are found outside `IndividualSimulationService.toDto()`, add `null, null,` at the same position (after `inputNet`, matching the new record shape) — cohort/aggregate paths are explicitly out of scope per the spec, so they always pass the "no conversion happened" null pair.

- [ ] **Step 6: Build**

```bash
mvn compile
```
Expected: `BUILD SUCCESS` — this surfaces any other positional-constructor call site the grep in Step 5 missed, since the record's shape changed.

- [ ] **Step 7: Run the full test suite**

```bash
mvn test
```
Expected: `BUILD SUCCESS`, same pass count as before plus the new tests from Tasks 2/4/5 run in their own repos. No existing test should fail — this task changes `IndividualSimulationService`'s internals but not its externally-observable behavior when `inputCurrency` is absent.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/simulation/service/IndividualSimulationService.java
git commit -m "feat(simulation): convert entered currency to working currency before the engine runs"
```

---

### Task 9: Payroll — `GET /api/payroll/ref/fx-rates` for the frontend

**Files:**
- Modify: `daf360-payroll-service/src/main/java/com/daf360/payroll/modules/ref/controller/RefController.java`

- [ ] **Step 1: Add the endpoint**

Add to the constructor-injected fields and constructor:

```java
    private final PaysRefRepository paysRefRepository;
    private final UsersRefRepository usersRefRepository;
    private final ProfileSyncService profileSyncService;
    private final PayrollFxRateRepository fxRateRepository;

    public RefController(PaysRefRepository paysRefRepository,
                         UsersRefRepository usersRefRepository,
                         ProfileSyncService profileSyncService,
                         PayrollFxRateRepository fxRateRepository) {
        this.paysRefRepository = paysRefRepository;
        this.usersRefRepository = usersRefRepository;
        this.profileSyncService = profileSyncService;
        this.fxRateRepository = fxRateRepository;
    }
```

Add the import: `import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;`.

Add, after `listUsers()`:

```java
    public record FxRateDto(String currencyCode, java.math.BigDecimal rateToEur) {}

    /** Currencies the simulator's amount field can offer, with their cached rate-to-EUR --
     *  the frontend never does its own FX math, it only needs the list of available codes. */
    @GetMapping("/fx-rates")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<FxRateDto> listFxRates() {
        return fxRateRepository.findAll().stream()
                .map(r -> new FxRateDto(r.getCurrencyCode(), r.getRateToEur()))
                .toList();
    }
```

- [ ] **Step 2: Build**

```bash
mvn compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/daf360/payroll/modules/ref/controller/RefController.java
git commit -m "feat(ref): expose GET /api/payroll/ref/fx-rates for the simulator's currency picker"
```

---

### Task 10: Frontend — `PayrollApiService` types + call

**Files:**
- Modify: `daf360-payroll-frontend/src/app/core/payroll-api.service.ts`

- [ ] **Step 1: Add `inputCurrency` to `SimulationRequest`**

Find:

```typescript
export interface SimulationRequest {
  paysId: number;
  /** NET_TO_BRUT: required. BRUT_TO_NET: ignored. */
  inputNet?: number;
  /** BRUT_TO_NET: required. NET_TO_BRUT: ignored. */
  inputGross?: number;
  /** Defaults to NET_TO_BRUT when omitted (backward-compatible). */
  mode?: SimulationMode;
  profileUserId?: number | null;
  contractType?: string;
  joursTravailes?: number;            // working days for FIXE_JOURNALIER rubriques (default 22)
  selectedBenefitCodes?: string[];    // null = apply all; empty = none
  isMarried?: boolean;                // family IRPP-base deduction; omitted = not married
  children?: number;                  // dependent children count; omitted = 0
  candidateLabel?: string;            // free-text for PDF
  poste?: string;
  grade?: string;
  discipline?: string;
}
```

Replace with (adds `inputCurrency` at the end):

```typescript
export interface SimulationRequest {
  paysId: number;
  /** NET_TO_BRUT: required. BRUT_TO_NET: ignored. */
  inputNet?: number;
  /** BRUT_TO_NET: required. NET_TO_BRUT: ignored. */
  inputGross?: number;
  /** Defaults to NET_TO_BRUT when omitted (backward-compatible). */
  mode?: SimulationMode;
  profileUserId?: number | null;
  contractType?: string;
  joursTravailes?: number;            // working days for FIXE_JOURNALIER rubriques (default 22)
  selectedBenefitCodes?: string[];    // null = apply all; empty = none
  isMarried?: boolean;                // family IRPP-base deduction; omitted = not married
  children?: number;                  // dependent children count; omitted = 0
  candidateLabel?: string;            // free-text for PDF
  poste?: string;
  grade?: string;
  discipline?: string;
  /** Currency inputNet/inputGross was actually typed in. Omitted = the country's working
   *  currency (today's behavior). When set and different, the backend converts before running
   *  the engine and the result's own inputCurrency/inputAmountOriginal reflect what was typed. */
  inputCurrency?: string;
}
```

- [ ] **Step 2: Add `inputCurrency`/`inputAmountOriginal` to `SimulationResultDto`**

Find:

```typescript
export interface SimulationResultDto {
  id: number;
  paysId: number;
  profileUserId: number | null;
  parameterSetId: number;
  simulationType: string;
  contractType: string;
  // 5 strates
  inputNet: number;            // S1
  netTaxable: number;          // S2
```

Replace with:

```typescript
export interface SimulationResultDto {
  id: number;
  paysId: number;
  profileUserId: number | null;
  parameterSetId: number;
  simulationType: string;
  contractType: string;
  // 5 strates
  inputNet: number;            // S1
  /** Currency actually typed, null = the country's working currency (the common case). */
  inputCurrency: string | null;
  /** Amount as typed, in inputCurrency. Null exactly when inputCurrency is null. */
  inputAmountOriginal: number | null;
  netTaxable: number;          // S2
```

- [ ] **Step 3: Add an `FxRateDto` type and `getFxRates()` method**

Add near the other small reference-data interfaces (e.g. near `PaysDto`):

```typescript
export interface FxRateDto {
  currencyCode: string;
  rateToEur: number;
}
```

Add the method near `getActiveParameterSet`:

```typescript
  getFxRates(): Observable<FxRateDto[]> {
    return this.http.get<FxRateDto[]>(`${this.base}/ref/fx-rates`);
  }
```

- [ ] **Step 4: Build**

```bash
cd daf360-payroll-frontend
npx tsc --noEmit -p tsconfig.json
```
Expected: no new type errors (existing unrelated errors, if any, are not this task's concern — only confirm nothing new appears referencing these two interfaces or this file).

- [ ] **Step 5: Commit**

```bash
git add src/app/core/payroll-api.service.ts
git commit -m "feat(api): add inputCurrency to SimulationRequest/Result, add getFxRates()"
```

---

### Task 11: Frontend — currency picker on the simulator form

**Files:**
- Modify: `daf360-payroll-frontend/src/app/modules/simulator/simulator.component.ts`
- Modify: `daf360-payroll-frontend/src/app/modules/simulator/simulator.component.html`

- [ ] **Step 1: Add the form control**

In the `form` FormGroup (currently at line 346), add `inputCurrency` right after `inputGross`:

```typescript
  readonly form = this.fb.group({
    paysId:         [null as number | null, [Validators.required]],
    profileUserId:  [null as number | null],
    inputNet:       [null as number | null, [Validators.required, Validators.min(1)]],
    inputGross:     [null as number | null],  // used when mode = BRUT_TO_NET
    inputCurrency:  [null as string | null],  // null = the country's working currency
    contractType:   ['CDI'],
    joursTravailes: [22, [Validators.min(1), Validators.max(31)]],
    isMarried:      [false],
    children:       [0, [Validators.min(0)]],
    candidateLabel: [''],
    poste:          [''],
    grade:          [''],
    discipline:     [''],
  });
```

- [ ] **Step 2: Add the available-currencies signal + fetch**

Add near `readonly paysList = signal<PaysDto[]>([]);` (around line 329):

```typescript
  /** `GET /api/payroll/ref/fx-rates` — currencies the amount field's picker can offer, besides
   *  the country's own working currency (always offered, never needs a fetched rate). */
  readonly fxRates = signal<FxRateDto[]>([]);
```

Add `FxRateDto` to the existing import from `'../../core/payroll-api.service'` at the top of the file.

Add it as a fourth key in `loadInitialReferences()`'s existing `forkJoin`. Current method:

```typescript
  private loadInitialReferences(): void {
    forkJoin({
      pays:        this.api.listPays().pipe(catchError(() => of([] as PaysDto[]))),
      grades:      this.hrRef.listGrades().pipe(catchError(() => of([] as RefDataItemDto[]))),
      disciplines: this.hrRef.listDisciplines().pipe(catchError(() => of([] as RefDataItemDto[]))),
    }).pipe(takeUntil(this.destroy$))
      .subscribe(({ pays, grades, disciplines }) => {
        this.paysList.set(pays);
        this.grades.set(grades);
        this.disciplines.set(disciplines);
        this.firstLoad.set(false);
      });
  }
```

Replace with:

```typescript
  private loadInitialReferences(): void {
    forkJoin({
      pays:        this.api.listPays().pipe(catchError(() => of([] as PaysDto[]))),
      grades:      this.hrRef.listGrades().pipe(catchError(() => of([] as RefDataItemDto[]))),
      disciplines: this.hrRef.listDisciplines().pipe(catchError(() => of([] as RefDataItemDto[]))),
      fxRates:     this.api.getFxRates().pipe(catchError(() => of([] as FxRateDto[]))),
    }).pipe(takeUntil(this.destroy$))
      .subscribe(({ pays, grades, disciplines, fxRates }) => {
        this.paysList.set(pays);
        this.grades.set(grades);
        this.disciplines.set(disciplines);
        this.fxRates.set(fxRates);
        this.firstLoad.set(false);
      });
  }
```

- [ ] **Step 3: Add a `currencyOptions` computed and `currentCurrency` / `setCurrency` helpers**

Add near `readonly currency = computed(...)` (around line 378):

```typescript
  /** The field's current currency: what's explicitly selected, or the country's working
   *  currency when nothing has been chosen yet (today's default, unchanged). */
  readonly currentCurrency = computed(() =>
    this.formValue().inputCurrency ?? this.currency());

  /** Working currency first (it's always valid, even before fxRates has loaded), then every
   *  other currency payroll has a cached rate for, alphabetically. */
  readonly currencyOptions = computed((): SelectOption[] => {
    const working = this.currency();
    const others = this.fxRates()
      .map(r => r.currencyCode)
      .filter(c => c !== working)
      .sort();
    return [working, ...others]
      .filter((c): c is string => !!c)
      .map(c => ({ value: c, label: c }));
  });

  setCurrency(selected: string[]): void {
    const picked = selected[0];
    // Treat "picked back the working currency" as null, not as an explicit match: it's the
    // same thing submit()/IndividualSimulationService already treat as "no conversion".
    this.form.get('inputCurrency')!.setValue(picked && picked !== this.currency() ? picked : null);
  }

  readonly currencySelection = computed((): string[] => [this.currentCurrency()].filter(Boolean) as string[]);
```

- [ ] **Step 4: Replace the static currency suffix with a select, in the template**

In `simulator.component.html`, find the NET_TO_BRUT branch (around line 333-354):

```html
                    @if (mode() === "NET_TO_BRUT") {
                      <daf-form-field
                        [options]="{
                          label:
                            'PAYROLL.SIMULATOR.STEP2.NET_LABEL'
                            | translate: { period: periodAdjective() },
                          type: 'number',
                          required: true,
                          align: 'end',
                          placeholder:
                            'PAYROLL.SIMULATOR.STEP2.NET_PLACEHOLDER' | translate,
                          suffixText: currency(),
                          hint:
                            period() === 'YEARLY'
                              ? ('PAYROLL.SIMULATOR.STEP2.AMOUNT_HINT_YEARLY'
                                | translate)
                              : ('PAYROLL.SIMULATOR.STEP2.AMOUNT_HINT_MONTHLY'
                                | translate),
                        }"
                        [value]="fieldValue('inputNet')"
                        (valueChange)="setFieldValue('inputNet', $event)"
                      />
                    } @else {
                      <daf-form-field
                        [options]="{
                          label:
                            'PAYROLL.SIMULATOR.STEP2.GROSS_LABEL'
                            | translate: { period: periodAdjective() },
                          type: 'number',
                          required: true,
                          align: 'end',
                          placeholder:
                            'PAYROLL.SIMULATOR.STEP2.GROSS_PLACEHOLDER' | translate,
                          suffixText: currency(),
                          hint:
                            period() === 'YEARLY'
                              ? ('PAYROLL.SIMULATOR.STEP2.AMOUNT_HINT_YEARLY'
                                | translate)
                              : ('PAYROLL.SIMULATOR.STEP2.AMOUNT_HINT_MONTHLY'
                                | translate),
                        }"
                        [value]="fieldValue('inputGross')"
                        (valueChange)="setFieldValue('inputGross', $event)"
                      />
                    }
                  </div>
                  <div class="flex-1 min-w-0">
```

Replace with (the `suffixText` line is removed from each `daf-form-field`; a `daf-select` is added right after each one, inside the same wrapping `<div class="flex-1 min-w-0">`, as a second stacked control sharing the row):

```html
                    @if (mode() === "NET_TO_BRUT") {
                      <daf-form-field
                        [options]="{
                          label:
                            'PAYROLL.SIMULATOR.STEP2.NET_LABEL'
                            | translate: { period: periodAdjective() },
                          type: 'number',
                          required: true,
                          align: 'end',
                          placeholder:
                            'PAYROLL.SIMULATOR.STEP2.NET_PLACEHOLDER' | translate,
                          hint:
                            period() === 'YEARLY'
                              ? ('PAYROLL.SIMULATOR.STEP2.AMOUNT_HINT_YEARLY'
                                | translate)
                              : ('PAYROLL.SIMULATOR.STEP2.AMOUNT_HINT_MONTHLY'
                                | translate),
                        }"
                        [value]="fieldValue('inputNet')"
                        (valueChange)="setFieldValue('inputNet', $event)"
                      />
                    } @else {
                      <daf-form-field
                        [options]="{
                          label:
                            'PAYROLL.SIMULATOR.STEP2.GROSS_LABEL'
                            | translate: { period: periodAdjective() },
                          type: 'number',
                          required: true,
                          align: 'end',
                          placeholder:
                            'PAYROLL.SIMULATOR.STEP2.GROSS_PLACEHOLDER' | translate,
                          hint:
                            period() === 'YEARLY'
                              ? ('PAYROLL.SIMULATOR.STEP2.AMOUNT_HINT_YEARLY'
                                | translate)
                              : ('PAYROLL.SIMULATOR.STEP2.AMOUNT_HINT_MONTHLY'
                                | translate),
                        }"
                        [value]="fieldValue('inputGross')"
                        (valueChange)="setFieldValue('inputGross', $event)"
                      />
                    }
                    <daf-select
                      class="mt-2 block"
                      [options]="currencyOptions()"
                      [config]="{
                        label: 'PAYROLL.SIMULATOR.STEP2.CURRENCY_LABEL' | translate,
                        hint: 'PAYROLL.SIMULATOR.STEP2.CURRENCY_HINT' | translate: { working: currency() },
                      }"
                      [selected]="currencySelection()"
                      (selectedChange)="setCurrency($event)"
                    />
                  </div>
                  <div class="flex-1 min-w-0">
```

- [ ] **Step 5: Build**

```bash
cd daf360-payroll-frontend
npx tsc --noEmit -p tsconfig.json
```
Expected: no new errors.

- [ ] **Step 6: Commit**

```bash
git add src/app/modules/simulator/simulator.component.ts src/app/modules/simulator/simulator.component.html
git commit -m "feat(simulator): add a currency picker next to the amount field"
```

---

### Task 12: Frontend — wire currency into `submit()` and `prefill()`

**Files:**
- Modify: `daf360-payroll-frontend/src/app/modules/simulator/simulator.component.ts`

- [ ] **Step 1: Send `inputCurrency` on submit**

Find, in `submit()`:

```typescript
      inputNet:   currentMode === 'NET_TO_BRUT' ? Number(v.inputNet)   / divisor : undefined,
      inputGross: currentMode === 'BRUT_TO_NET' ? Number(v.inputGross) / divisor : undefined,
```

Replace with:

```typescript
      inputNet:   currentMode === 'NET_TO_BRUT' ? Number(v.inputNet)   / divisor : undefined,
      inputGross: currentMode === 'BRUT_TO_NET' ? Number(v.inputGross) / divisor : undefined,
      inputCurrency: v.inputCurrency || undefined,
```

- [ ] **Step 2: Restore it on "Dupliquer" / "Modifier la simulation"**

Find, in `prefill()`:

```typescript
    this.form.patchValue({
      profileUserId:  isEmployee ? r.profileUserId : null,
      inputNet:       mode === 'NET_TO_BRUT' ? r.inputNet : null,
      inputGross:     mode === 'BRUT_TO_NET' ? r.gross : null,
      candidateLabel: r.candidateLabel ?? '',
      poste:          r.poste ?? '',
      grade:          r.grade ?? '',
      discipline:     r.discipline ?? '',
    });
```

Replace with (when `inputCurrency`/`inputAmountOriginal` are present on the result, restore the ORIGINAL typed amount in its original currency rather than the converted working-currency figure — a faithful "what did I actually type" restore, per the spec):

```typescript
    const hadCustomCurrency = r.inputCurrency != null && r.inputAmountOriginal != null;
    this.form.patchValue({
      profileUserId:  isEmployee ? r.profileUserId : null,
      inputNet:       mode === 'NET_TO_BRUT'
        ? (hadCustomCurrency ? r.inputAmountOriginal : r.inputNet) : null,
      inputGross:     mode === 'BRUT_TO_NET'
        ? (hadCustomCurrency ? r.inputAmountOriginal : r.gross) : null,
      inputCurrency:  hadCustomCurrency ? r.inputCurrency : null,
      candidateLabel: r.candidateLabel ?? '',
      poste:          r.poste ?? '',
      grade:          r.grade ?? '',
      discipline:     r.discipline ?? '',
    });
```

- [ ] **Step 3: Build**

```bash
npx tsc --noEmit -p tsconfig.json
```
Expected: no new errors.

- [ ] **Step 4: Commit**

```bash
git add src/app/modules/simulator/simulator.component.ts
git commit -m "feat(simulator): send/restore the entered currency on submit and Dupliquer"
```

---

### Task 13: Frontend — show the original entry on the result view

**Files:**
- Modify: `daf360-payroll-frontend/src/app/modules/simulator/simulator.component.ts`

The "Entrées du calcul" panel is NOT static HTML per row — `simulator.component.html:633-643` loops `@for (row of block.rows; ...)` over data built by the `detailBlocks` computed in the `.ts` file. The new row goes there, not in the template.

- [ ] **Step 1: Add the row to the `'inputs'` block**

Find, in `detailBlocks` (the `'inputs'` block's `rows` array):

```typescript
      {
        id: 'inputs', title: this.t('PAYROLL.SIMULATOR.DETAIL.BLOCK_INPUTS'),
        rows: keep([
          { key: this.t('PAYROLL.SIMULATOR.LIST.FILTER.MODE'),
            value: this.t(`PAYROLL.SIMULATOR.MODE.${isNetToGross ? 'NET_TO_BRUT' : 'BRUT_TO_NET'}`) },
          // Le net visé seulement : il peut différer du net obtenu (résultat approximatif).
          // Le brut saisi, lui, est exactement la tuile « Brut mensuel » : pas répété.
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.INPUT_NET'),
            value: isNetToGross ? this.money(r.inputNet, cur, 2) : null },
          // Base de l'IRPP (ancienne barre S2 de la cascade) — le seul montant du calcul
          // qui n'est ni dans les KPI ni dans « Qui reçoit quoi ? ».
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.NET_TAXABLE'), value: this.money(r.netTaxable, cur, 2) },
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.BENEFITS'),
            value: benefits == null ? null : String(benefits.length) },
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.CONVERGENCE'),
            value: this.t(r.convergenceOk ? 'PAYROLL.SIMULATOR.DETAIL.CONVERGENCE_OK' : 'PAYROLL.SIMULATOR.DETAIL.CONVERGENCE_PARTIAL',
              { iterations: r.iterationsUsed }),
            ok: !!r.convergenceOk },
        ]),
      },
```

Replace with (one new row added right after `INPUT_NET`, `keep()` already drops it automatically when `value` is `null` — i.e. for every simulation that didn't use a custom currency, nothing changes on screen):

```typescript
      {
        id: 'inputs', title: this.t('PAYROLL.SIMULATOR.DETAIL.BLOCK_INPUTS'),
        rows: keep([
          { key: this.t('PAYROLL.SIMULATOR.LIST.FILTER.MODE'),
            value: this.t(`PAYROLL.SIMULATOR.MODE.${isNetToGross ? 'NET_TO_BRUT' : 'BRUT_TO_NET'}`) },
          // Le net visé seulement : il peut différer du net obtenu (résultat approximatif).
          // Le brut saisi, lui, est exactement la tuile « Brut mensuel » : pas répété.
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.INPUT_NET'),
            value: isNetToGross ? this.money(r.inputNet, cur, 2) : null },
          // Visible seulement si saisi dans une devise différente de celle du pays -- sinon
          // r.inputCurrency est null et keep() retire la ligne.
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.ENTERED_AS'),
            value: (r.inputCurrency && r.inputAmountOriginal != null)
              ? this.money(r.inputAmountOriginal, r.inputCurrency, 2) : null },
          // Base de l'IRPP (ancienne barre S2 de la cascade) — le seul montant du calcul
          // qui n'est ni dans les KPI ni dans « Qui reçoit quoi ? ».
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.NET_TAXABLE'), value: this.money(r.netTaxable, cur, 2) },
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.BENEFITS'),
            value: benefits == null ? null : String(benefits.length) },
          { key: this.t('PAYROLL.SIMULATOR.DETAIL.CONVERGENCE'),
            value: this.t(r.convergenceOk ? 'PAYROLL.SIMULATOR.DETAIL.CONVERGENCE_OK' : 'PAYROLL.SIMULATOR.DETAIL.CONVERGENCE_PARTIAL',
              { iterations: r.iterationsUsed }),
            ok: !!r.convergenceOk },
        ]),
      },
```

- [ ] **Step 2: Build**

```bash
npx tsc --noEmit -p tsconfig.json
```
Expected: no new errors.

- [ ] **Step 3: Commit**

```bash
git add src/app/modules/simulator/simulator.component.ts
git commit -m "feat(simulator): show the originally-entered amount/currency on the result view"
```

---

### Task 14: i18n keys

**Files:**
- Modify: `daf360-payroll-frontend/public/i18n/fr.json`
- Modify: `daf360-payroll-frontend/public/i18n/en.json`

- [ ] **Step 1: Add French keys**

In `fr.json`, find (inside `"STEP2"`):

```json
        "AMOUNT_HINT_MONTHLY": "Les résultats affichent le mensuel et l'annuel (× 12).",
        "CONTRACT_LABEL": "Type de contrat",
```

Insert between them:

```json
        "AMOUNT_HINT_MONTHLY": "Les résultats affichent le mensuel et l'annuel (× 12).",
        "CURRENCY_LABEL": "Devise saisie",
        "CURRENCY_HINT": "Devise de travail du pays : {{working}}. Choisissez une autre devise si vous saisissez dans une devise différente.",
        "CONTRACT_LABEL": "Type de contrat",
```

And, inside `"DETAIL"`, find:

```json
        "GROUP_EMPLOYEE": "Le salarié reçoit",
```

Insert right before it:

```json
        "ENTERED_AS": "Saisi comme",
        "GROUP_EMPLOYEE": "Le salarié reçoit",
```

- [ ] **Step 2: Add the matching English keys**

In `en.json`, find (inside `"STEP2"`):

```json
        "AMOUNT_HINT_MONTHLY": "Results show both the monthly and the yearly figure (× 12).",
        "CONTRACT_LABEL": "Contract type",
```

Insert between them:

```json
        "AMOUNT_HINT_MONTHLY": "Results show both the monthly and the yearly figure (× 12).",
        "CURRENCY_LABEL": "Entered currency",
        "CURRENCY_HINT": "The country's working currency is {{working}}. Pick a different one if you're entering in another currency.",
        "CONTRACT_LABEL": "Contract type",
```

And, inside `"DETAIL"`, find:

```json
        "GROUP_EMPLOYEE": "The employee receives",
```

Insert right before it:

```json
        "ENTERED_AS": "Entered as",
        "GROUP_EMPLOYEE": "The employee receives",
```

- [ ] **Step 3: Verify both files are still valid JSON**

```bash
cd daf360-payroll-frontend
node -e "JSON.parse(require('fs').readFileSync('public/i18n/fr.json'))" && echo "fr.json OK"
node -e "JSON.parse(require('fs').readFileSync('public/i18n/en.json'))" && echo "en.json OK"
```
Expected: both print `OK`.

- [ ] **Step 4: Commit**

```bash
git add public/i18n/fr.json public/i18n/en.json
git commit -m "i18n: add currency-picker and entered-as keys for the simulator"
```

---

### Task 15: End-to-end verification (manual)

**Files:** none — verification only.

- [ ] **Step 1: Rebuild and restart all three touched services locally**

```bash
cd daf360-facturation-service && docker compose -f ../docker-compose.dev.yml build daf360-facturation-backend && docker compose -f ../docker-compose.dev.yml up -d daf360-facturation-backend
cd ../daf360-payroll-service && docker compose -f ../docker-compose.dev.yml build daf360-payroll-backend && docker compose -f ../docker-compose.dev.yml up -d daf360-payroll-backend
cd ../daf360-payroll-frontend && docker compose -f ../docker-compose.dev.yml build daf360-payroll-frontend && docker compose -f ../docker-compose.dev.yml up -d daf360-payroll-frontend
```

- [ ] **Step 2: Force a sync cycle rather than waiting 15 minutes**

The payroll backend's `POST /api/payroll/ref/sync` endpoint (`RefController.triggerSync()`) already calls `profileSyncService.sync()`, which now also runs `syncFxRates()`. Call it with a valid bearer token (see this session's established JWT-minting approach for local testing) and confirm `payroll_fx_rates` is populated:

```bash
docker exec timesheet-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -d DAF360_PAYROLL -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM dbo.payroll_fx_rates;" -W
```
Expected: 13 (matching finance's currently-seeded count).

- [ ] **Step 3: Run the TND scenario this feature was built for**

Via `POST /api/payroll/simulations/individual` (or the UI once it's confirmed reachable): Tunisia, CDI, `mode: "BRUT_TO_NET"`, `inputGross: 198`, `inputCurrency: "TND"`.

Expected: the response's `gross` field equals 198 × 0.300 = **59.4000** (TND converted to the EUR working currency), and `inputCurrency: "TND"` / `inputAmountOriginal: 198` are present on the stored result. Cross-check by also running the same request with `inputGross: 59.4, inputCurrency` omitted — both should produce an identical `netInHand`/`irppAmount`/`loadedCost` (the engine receiving the same working-currency gross either way).

- [ ] **Step 4: Confirm the 422 path**

Same request with `inputCurrency: "XYZ"` (not a real currency, so finance has no `RATE_EUR_XYZ`).

Expected: HTTP 422 with a `detail` message naming `XYZ`, not a 500 and not a silently-wrong result.

- [ ] **Step 5: Report back**

Summarize the four checks above to the user, note that `payroll_fx_rates` and the `simulation_results` columns are local-only (migration applied locally, not yet on 192.168.1.233), and ask whether to push the three repos' commits and/or apply V34 to 233.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-11-06-simulator-currency-entry.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
