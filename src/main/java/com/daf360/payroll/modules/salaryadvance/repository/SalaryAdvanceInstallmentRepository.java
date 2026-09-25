package com.daf360.payroll.modules.salaryadvance.repository;

import com.daf360.payroll.modules.salaryadvance.entity.InstallmentStatus;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceInstallment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SalaryAdvanceInstallmentRepository extends JpaRepository<SalaryAdvanceInstallment, Long> {

    List<SalaryAdvanceInstallment> findBySalaryAdvanceIdOrderBySeqAsc(Long salaryAdvanceId);

    List<SalaryAdvanceInstallment> findBySalaryAdvanceIdInOrderBySeqAsc(Collection<Long> salaryAdvanceIds);

    boolean existsBySalaryAdvanceIdAndStatus(Long salaryAdvanceId, InstallmentStatus status);

    /**
     * The monthly deduction list: every line of one payroll month on an advance being repaid.
     * All statuses, so the screen shows what is already ticked off; the export keeps PLANNED.
     * Entity scope is applied by the service.
     */
    @Query("""
            SELECT i FROM SalaryAdvanceInstallment i, SalaryAdvance a
            WHERE a.id = i.salaryAdvanceId
              AND i.periodYear = :year AND i.periodMonth = :month
              AND a.status = com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceStatus.REPAYING
            ORDER BY a.profileUserId, i.seq
            """)
    List<SalaryAdvanceInstallment> findDueIn(@Param("year") int year, @Param("month") int month);
}
