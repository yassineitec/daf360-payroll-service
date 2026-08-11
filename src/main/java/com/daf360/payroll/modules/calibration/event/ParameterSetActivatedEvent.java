package com.daf360.payroll.modules.calibration.event;

import com.daf360.payroll.modules.parameterset.entity.ParameterSet;

/**
 * Published after a ParameterSet receives both HR and Finance approval and transitions
 * to ACTIVE status. Consumed by CalibrationFinanceService to generate F.04 budget lines
 * and F.07 forecast outputs.
 */
public record ParameterSetActivatedEvent(ParameterSet parameterSet) {}
