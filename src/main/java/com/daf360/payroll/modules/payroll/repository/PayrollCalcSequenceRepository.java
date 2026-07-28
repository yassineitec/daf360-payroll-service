package com.daf360.payroll.modules.payroll.repository;

import com.daf360.payroll.modules.payroll.entity.PayrollCalcSequence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayrollCalcSequenceRepository extends JpaRepository<PayrollCalcSequence, Long> {

    Optional<PayrollCalcSequence> findByCountryIdAndActiveTrue(Long countryId);
}
