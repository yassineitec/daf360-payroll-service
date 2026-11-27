package com.daf360.payroll.modules.payroll.leavesettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OvertimeCapRepository extends JpaRepository<OvertimeCap, Long> {

    Optional<OvertimeCap> findByScopeAndProfileUserId(String scope, Long profileUserId);

    Optional<OvertimeCap> findByScopeAndPaysIdAndRoleId(String scope, Long paysId, Long roleId);

    List<OvertimeCap> findByScopeAndPaysId(String scope, Long paysId);
}
