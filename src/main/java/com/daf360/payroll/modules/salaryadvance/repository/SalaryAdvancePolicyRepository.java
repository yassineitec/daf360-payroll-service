package com.daf360.payroll.modules.salaryadvance.repository;

import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalaryAdvancePolicyRepository extends JpaRepository<SalaryAdvancePolicy, Long> {

    Optional<SalaryAdvancePolicy> findByPaysId(Long paysId);

    List<SalaryAdvancePolicy> findAllByOrderByPaysIdAsc();
}
