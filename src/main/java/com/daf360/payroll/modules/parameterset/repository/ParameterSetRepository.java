package com.daf360.payroll.modules.parameterset.repository;

import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ParameterSetRepository extends JpaRepository<ParameterSet, Long> {

    Optional<ParameterSet> findFirstByPaysIdAndStatusOrderByVersionDesc(Long paysId, String status);

    List<ParameterSet> findByPaysIdOrderByVersionDesc(Long paysId);

    Optional<ParameterSet> findTopByPaysIdOrderByVersionDesc(Long paysId);
}
