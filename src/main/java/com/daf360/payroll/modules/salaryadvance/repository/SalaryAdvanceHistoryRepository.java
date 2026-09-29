package com.daf360.payroll.modules.salaryadvance.repository;

import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SalaryAdvanceHistoryRepository extends JpaRepository<SalaryAdvanceHistory, Long> {

    List<SalaryAdvanceHistory> findBySalaryAdvanceIdOrderByCreatedAtAsc(Long salaryAdvanceId);

    /** The country's latest status changes, newest first — the admin change log. The history
     *  row carries no pays of its own: it is the advance's. */
    @Query("SELECT h FROM SalaryAdvanceHistory h, SalaryAdvance a "
         + "WHERE a.id = h.salaryAdvanceId AND a.paysId = :paysId ORDER BY h.createdAt DESC")
    List<SalaryAdvanceHistory> findLatestByPaysId(@Param("paysId") Long paysId, Pageable page);
}
