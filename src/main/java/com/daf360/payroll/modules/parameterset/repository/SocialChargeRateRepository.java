package com.daf360.payroll.modules.parameterset.repository;

import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocialChargeRateRepository extends JpaRepository<SocialChargeRate, Long> {

    List<SocialChargeRate> findByParameterSetId(Long parameterSetId);

    List<SocialChargeRate> findByParameterSetIdAndContractType(Long parameterSetId, String contractType);

    void deleteByParameterSetId(Long parameterSetId);
}
