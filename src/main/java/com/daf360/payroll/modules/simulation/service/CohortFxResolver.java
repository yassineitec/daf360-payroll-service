package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.ref.entity.PayrollFxRate;
import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse.CohortFx;
import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse.CohortMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one FX resolution used by the cohort (A6 / DEC7): 1 unit of the country's payroll
 * currency = ? EUR and ? CHF.
 *
 * <ol>
 *   <li>{@code payroll_fx_rates} — the rates synced from finance (each relative to EUR);
 *       CHF is composed through EUR: local→EUR ÷ CHF→EUR.</li>
 *   <li>{@code payroll_countries} — the static per-country rate.</li>
 *   <li>{@code app.fx-rates} — the configuration fallback.</li>
 * </ol>
 * A rate of exactly 1 between two different currencies is never trusted (it is the default
 * value of an unset rate, e.g. Tunisia's {@code eur_rate = 1.000} for TND): that source is
 * skipped, and if no source is left the converted amount is {@code null} with a message —
 * never a silent 1.0. Stored rates are read only, never changed.
 */
@Component
public class CohortFxResolver {

    public static final String SRC_IDENTITY = "IDENTITY";
    public static final String SRC_SYNCED = "payroll_fx_rates";
    public static final String SRC_STATIC = "payroll_countries";
    public static final String SRC_CONFIG = "app.fx-rates";

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final FxSnapshotService fxSnapshotService;
    private final PayrollFxRateRepository fxRateRepo;
    private final PayrollCountryRepository countryRepo;
    private final AppProperties appProperties;

    public CohortFxResolver(FxSnapshotService fxSnapshotService, PayrollFxRateRepository fxRateRepo,
                            PayrollCountryRepository countryRepo, AppProperties appProperties) {
        this.fxSnapshotService = fxSnapshotService;
        this.fxRateRepo = fxRateRepo;
        this.countryRepo = countryRepo;
        this.appProperties = appProperties;
    }

    /** Rates plus the messages explaining any missing one. */
    public record Resolution(CohortFx fx, List<CohortMessage> messages) {

        public BigDecimal toEur(BigDecimal local) {
            return local == null || fx.eurRate() == null ? null : local.multiply(fx.eurRate()).setScale(4, RoundingMode.HALF_UP);
        }

        public BigDecimal toChf(BigDecimal local) {
            return local == null || fx.chfRate() == null ? null : local.multiply(fx.chfRate()).setScale(4, RoundingMode.HALF_UP);
        }

        public String summary() {
            return "EUR: " + (fx.eurRate() == null ? "indisponible" : fx.eurSource())
                    + " ; CHF: " + (fx.chfRate() == null ? "indisponible" : fx.chfSource());
        }
    }

    private record Rate(BigDecimal value, String source) {}

    public Resolution resolve(Long paysId) {
        String local = fxSnapshotService.localCurrency(paysId);
        List<CohortMessage> messages = new ArrayList<>();
        if (local == null || local.isBlank()) {
            messages.add(CohortMessage.warn("FX_NO_CURRENCY",
                    "La devise de paie de ce pays n'est pas configurée : aucune conversion EUR / CHF."));
            return new Resolution(new CohortFx(null, null, null, null, null, null), messages);
        }
        String cur = local.toUpperCase();
        Optional<PayrollCountry> country = countryRepo.findByPaysIdAndActiveTrue(paysId);
        AppProperties.FxRateEntry cfg = Optional.ofNullable(appProperties.getFxRates())
                .map(m -> m.get(String.valueOf(paysId))).orElse(null);

        Rate eur = pick(cur, "EUR", synced(cur, "EUR"),
                country.map(PayrollCountry::getEurRate).orElse(null),
                cfg == null ? null : cfg.getEur(), messages);
        Rate chf = pick(cur, "CHF", synced(cur, "CHF"),
                country.map(PayrollCountry::getChfRate).orElse(null),
                cfg == null ? null : cfg.getChf(), messages);

        OffsetDateTime syncedAt = (SRC_SYNCED.equals(eur.source()) || SRC_SYNCED.equals(chf.source()))
                ? fxRateRepo.findByCurrencyCode(cur).map(PayrollFxRate::getSyncedAt)
                    .map(t -> t.atOffset(ZoneOffset.UTC)).orElse(null)
                : null;
        return new Resolution(new CohortFx(cur, eur.value(), eur.source(), chf.value(), chf.source(), syncedAt),
                messages);
    }

    /** local→target from payroll_fx_rates (both relative to EUR), or null. */
    private BigDecimal synced(String local, String target) {
        Function<String, BigDecimal> toEur = c -> "EUR".equals(c) ? BigDecimal.ONE
                : fxRateRepo.findByCurrencyCode(c).map(PayrollFxRate::getRateToEur)
                    .filter(r -> r.signum() > 0).orElse(null);
        BigDecimal l = toEur.apply(local);
        BigDecimal t = toEur.apply(target);
        if (l == null || t == null) return null;
        return l.divide(t, MC);
    }

    private Rate pick(String local, String target, BigDecimal synced, BigDecimal stat, BigDecimal config,
                      List<CohortMessage> messages) {
        if (local.equals(target)) return new Rate(BigDecimal.ONE, SRC_IDENTITY);
        if (usable(synced)) return new Rate(synced, SRC_SYNCED);
        boolean rejectedOne = false;
        if (usable(stat)) {
            if (stat.compareTo(BigDecimal.ONE) != 0) return new Rate(stat, SRC_STATIC);
            rejectedOne = true;
        }
        if (usable(config)) {
            if (config.compareTo(BigDecimal.ONE) != 0) return new Rate(config, SRC_CONFIG);
            rejectedOne = true;
        }
        messages.add(CohortMessage.warn("FX_MISSING_" + target,
                "Aucun taux " + local + " → " + target + " fiable"
                        + (rejectedOne ? " (le seul taux configuré vaut 1,0, ce qui est impossible entre deux devises différentes)" : "")
                        + " : les montants en " + target + " ne sont pas affichés."));
        return new Rate(null, null);
    }

    private static boolean usable(BigDecimal r) {
        return r != null && r.signum() > 0;
    }
}
