package com.daf360.payroll.modules.simulation.repository;

import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public interface SimulationResultRepository extends JpaRepository<SimulationResult, Long> {

    List<SimulationResult> findByPaysIdOrderBySimulatedAtDesc(Long paysId);

    List<SimulationResult> findByPaysIdAndSimulationTypeOrderBySimulatedAtDesc(Long paysId, String type);

    List<SimulationResult> findByCohortId(Long cohortId);

    /**
     * Filtre commun de la liste `/payroll/simulator` : simulations INDIVIDUELLES d'un pays
     * (les lignes de cohorte partagent la table et n'y ont pas leur place). Chaque critère
     * optionnel est neutre quand il vaut null. `q` est déjà en minuscules et entouré de `%`.
     */
    String INDIVIDUAL_FILTER = """
            r.paysId = :paysId
            and r.simulationType = 'INDIVIDUAL'
            and (:contractType is null or r.contractType = :contractType)
            and (:mode is null or r.mode = :mode)
            and (:partialOnly = false or r.convergenceOk = false)
            and (:subject is null
                 or (:subject = 'EMPLOYEE' and r.profileUserId is not null)
                 or (:subject = 'CANDIDATE' and r.profileUserId is null))
            and (:q is null
                 or lower(r.candidateLabel) like :q
                 or lower(r.poste) like :q
                 or lower(r.grade) like :q
                 or lower(r.discipline) like :q
                 or exists (select 1 from UsersRef u where u.id = r.profileUserId and lower(u.fullName) like :q))
            """;

    @Query(value = "select r from SimulationResult r where " + INDIVIDUAL_FILTER,
           countQuery = "select count(r) from SimulationResult r where " + INDIVIDUAL_FILTER)
    Page<SimulationResult> searchIndividual(@Param("paysId") Long paysId,
                                            @Param("contractType") String contractType,
                                            @Param("mode") String mode,
                                            @Param("subject") String subject,
                                            @Param("q") String q,
                                            @Param("partialOnly") boolean partialOnly,
                                            Pageable pageable);

    /**
     * Chiffres du bandeau KPI, sur le même périmètre que la liste (toutes pages confondues).
     * Le « mois » est borné par `monthStart` (1er du mois en cours) et `prevMonthStart`
     * (1er du mois précédent), calculés par le service.
     */
    interface IndividualSummary {
        Long getTotal();
        Long getEmployees();
        Long getMonthCount();
        Long getPrevMonthCount();
        Long getPartialCount();
        /** `avg()` JPQL rend un Double, quel que soit le type de la colonne. */
        Double getAvgLoadedCost();
        BigDecimal getMinLoadedCost();
        BigDecimal getMaxLoadedCost();
        Double getAvgRatio();
        Double getPrevMonthAvgRatio();
    }

    @Query("select count(r) as total, "
         + "sum(case when r.profileUserId is not null then 1 else 0 end) as employees, "
         + "sum(case when r.simulatedAt >= :monthStart then 1 else 0 end) as monthCount, "
         + "sum(case when r.simulatedAt >= :prevMonthStart and r.simulatedAt < :monthStart then 1 else 0 end) as prevMonthCount, "
         + "sum(case when r.convergenceOk = false then 1 else 0 end) as partialCount, "
         + "avg(r.loadedCost) as avgLoadedCost, min(r.loadedCost) as minLoadedCost, max(r.loadedCost) as maxLoadedCost, "
         + "avg(r.costNetRatio) as avgRatio, "
         + "avg(case when r.simulatedAt >= :prevMonthStart and r.simulatedAt < :monthStart then r.costNetRatio end) as prevMonthAvgRatio "
         + "from SimulationResult r where " + INDIVIDUAL_FILTER)
    IndividualSummary summarizeIndividual(@Param("paysId") Long paysId,
                                          @Param("contractType") String contractType,
                                          @Param("mode") String mode,
                                          @Param("subject") String subject,
                                          @Param("q") String q,
                                          @Param("partialOnly") boolean partialOnly,
                                          @Param("monthStart") OffsetDateTime monthStart,
                                          @Param("prevMonthStart") OffsetDateTime prevMonthStart);
}
