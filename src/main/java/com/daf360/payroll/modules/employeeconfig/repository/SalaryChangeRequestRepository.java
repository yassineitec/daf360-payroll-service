package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.SalaryChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalaryChangeRequestRepository extends JpaRepository<SalaryChangeRequest, Long> {

    /** At most one per employee (UX_scr_one_pending). */
    Optional<SalaryChangeRequest> findFirstByProfileUserIdAndStatus(Long profileUserId, String status);

    List<SalaryChangeRequest> findByProfileUserIdOrderBySubmittedAtDesc(Long profileUserId);

    List<SalaryChangeRequest> findByStatusOrderBySubmittedAtAsc(String status);
}
