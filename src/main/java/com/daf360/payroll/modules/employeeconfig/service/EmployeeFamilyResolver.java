package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The employee's family situation (married, number of children) for the IRPP family
 * deduction. The RH profile ({@code employee_profiles.marital_status / number_of_children}) is
 * the single source of truth; {@code employee_payroll_config.is_married / children_count} is
 * only a cache of the last value read from RH, used when RH cannot be reached.
 *
 * <p>Before this, payroll kept its own editable copy: it drifted from the RH profile, and the
 * Rémunération tab's save (which never sent it) reset it to "single, 0 children".
 */
@Slf4j
@Component
public class EmployeeFamilyResolver {

    /** @param fromHr false = RH unreachable or no RH profile: the cached config value is used. */
    public record FamilySituation(boolean married, int childrenCount, boolean fromHr) {
        public static final FamilySituation NONE = new FamilySituation(false, 0, false);
    }

    private final HrEmployeeClient hrEmployeeClient;

    public EmployeeFamilyResolver(HrEmployeeClient hrEmployeeClient) {
        this.hrEmployeeClient = hrEmployeeClient;
    }

    /** Calls RH, then falls back to the cached config value. */
    public FamilySituation resolve(Long profileUserId, EmployeePayrollConfig cached) {
        return resolve(hrEmployeeClient.findEmployeeByUserId(profileUserId), cached);
    }

    /** Same, with an RH answer the caller already fetched (avoids a second HTTP call). */
    public FamilySituation resolve(Optional<HrEmployeeClient.HrEmployeeDto> hr, EmployeePayrollConfig cached) {
        // RH always sends `married` (false when the profile has no marital status); null
        // means an older RH build that predates these fields — keep the cached value then.
        if (hr != null && hr.isPresent() && hr.get().married() != null) {
            HrEmployeeClient.HrEmployeeDto dto = hr.get();
            int children = dto.childrenCount() != null ? Math.max(dto.childrenCount(), 0) : 0;
            return new FamilySituation(Boolean.TRUE.equals(dto.married()), children, true);
        }
        if (cached == null) return FamilySituation.NONE;
        return new FamilySituation(
                Boolean.TRUE.equals(cached.getIsMarried()),
                cached.getChildrenCount() != null ? cached.getChildrenCount() : 0,
                false);
    }
}
