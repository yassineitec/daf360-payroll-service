package com.daf360.payroll.modules.payroll.repository;

import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Résultats d'un pays dont le mois tombe dans [{@code fromKey}, {@code toKey}], bornes
     * incluses, chaque mois encodé {@code année * 100 + mois} (202603 = mars 2026).
     */
    @Query("SELECT r FROM PayrollResult r WHERE r.paysId = :paysId "
         + "AND (r.periodYear * 100 + r.periodMonth) BETWEEN :fromKey AND :toKey")
    List<PayrollResult> findByPaysIdAndPeriodKeyBetween(@Param("paysId") Long paysId,
                                                        @Param("fromKey") int fromKey,
                                                        @Param("toKey") int toKey);

    /**
     * Collaborateurs ayant au moins un résultat sur [{@code fromKey}, {@code toKey}] (même
     * encodage que ci-dessus), tous pays confondus quand {@code paysId} est nul.
     */
    @Query("SELECT DISTINCT r.employeeId FROM PayrollResult r "
         + "WHERE (:paysId IS NULL OR r.paysId = :paysId) "
         + "AND (r.periodYear * 100 + r.periodMonth) BETWEEN :fromKey AND :toKey")
    List<Long> findEmployeeIdsWithResultsBetween(@Param("paysId") Long paysId,
                                                 @Param("fromKey") int fromKey,
                                                 @Param("toKey") int toKey);

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
