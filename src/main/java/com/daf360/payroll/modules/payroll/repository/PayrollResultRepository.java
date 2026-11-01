package com.daf360.payroll.modules.payroll.repository;

import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface PayrollResultRepository extends JpaRepository<PayrollResult, Long> {

    List<PayrollResult> findByEmployeeIdOrderByPeriodYearDescPeriodMonthDesc(Long employeeId);

    Optional<PayrollResult> findByEmployeeIdAndPeriodYearAndPeriodMonth(Long employeeId, int year, int month);

    List<PayrollResult> findByCountryIdAndPeriodYearAndPeriodMonth(Long countryId, int periodYear, int periodMonth);

    /** Most recent calculated period for a country — anchors the results summary. */
    Optional<PayrollResult> findTopByPaysIdOrderByPeriodYearDescPeriodMonthDesc(Long paysId);

    List<PayrollResult> findByPaysIdAndPeriodYearAndPeriodMonth(Long paysId, int periodYear, int periodMonth);

    /**
     * Le DERNIER calcul de chaque collaborateur parmi {@code rows} (même période). Chaque
     * lancement insère un nouveau résultat (l'enregistrement est immuable) : relancer un mois
     * laisse l'ancien en base, et l'additionner compterait ce collaborateur deux fois. Le plus
     * récent l'emporte (date de calcul, puis identifiant).
     */
    static List<PayrollResult> latestPerEmployee(List<PayrollResult> rows) {
        Comparator<PayrollResult> newest = Comparator
            .comparing(PayrollResult::getCalculatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(PayrollResult::getId, Comparator.nullsFirst(Comparator.naturalOrder()));
        return new ArrayList<>(rows.stream()
            .collect(Collectors.toMap(PayrollResult::getEmployeeId, Function.identity(),
                BinaryOperator.maxBy(newest), LinkedHashMap::new))
            .values());
    }
}
