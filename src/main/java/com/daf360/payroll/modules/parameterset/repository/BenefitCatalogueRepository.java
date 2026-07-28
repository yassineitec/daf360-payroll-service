package com.daf360.payroll.modules.parameterset.repository;

import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BenefitCatalogueRepository extends JpaRepository<BenefitCatalogue, Long> {

    List<BenefitCatalogue> findByParameterSetId(Long parameterSetId);

    void deleteByParameterSetId(Long parameterSetId);
}
