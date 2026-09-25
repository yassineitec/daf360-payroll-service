package com.daf360.payroll.modules.salaryadvance.repository;

import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvance;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SalaryAdvanceRepository extends JpaRepository<SalaryAdvance, Long> {

    List<SalaryAdvance> findByProfileUserIdOrderByCreatedAtDesc(Long profileUserId);

    boolean existsByProfileUserIdAndStatusIn(Long profileUserId, Collection<SalaryAdvanceStatus> statuses);

    // Entity scope is applied in SalaryAdvanceService (a LIST-mode role covers several pays).
    List<SalaryAdvance> findByStatusOrderByCreatedAtAsc(SalaryAdvanceStatus status);

    List<SalaryAdvance> findByStatusInOrderByCreatedAtDesc(Collection<SalaryAdvanceStatus> statuses);
}
