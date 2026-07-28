package com.daf360.payroll.modules.payroll.repository;

import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollParamSetRepository extends JpaRepository<PayrollParamSet, Long> {

    Optional<PayrollParamSet> findByCountryIdAndStatus(Long countryId, String status);

    List<PayrollParamSet> findByCountryIdOrderByVersionNumberDesc(Long countryId);
}
