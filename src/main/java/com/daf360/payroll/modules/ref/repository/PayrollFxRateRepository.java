package com.daf360.payroll.modules.ref.repository;

import com.daf360.payroll.modules.ref.entity.PayrollFxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayrollFxRateRepository extends JpaRepository<PayrollFxRate, String> {

    Optional<PayrollFxRate> findByCurrencyCode(String currencyCode);
}
