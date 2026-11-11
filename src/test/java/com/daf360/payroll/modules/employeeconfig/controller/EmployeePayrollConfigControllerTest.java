package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetRequest;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollConfigService;
import com.daf360.payroll.modules.employeeconfig.service.RemunerationSimulationService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeePayrollConfigControllerTest {

    @Mock EmployeePayrollConfigService service;
    @Mock UserContextService userContext;
    @Mock RemunerationSimulationService remunerationSimulation;

    @Test
    void calculateNet_delegatesToServiceAndReturnsNetInHand() {
        CalculateNetRequest req = new CalculateNetRequest(new BigDecimal("5000.000"), null, 179L, "CDI");
        when(service.calculateNet(10L, req))
                .thenReturn(new CalculateNetResponse(new BigDecimal("3820.500")));

        EmployeePayrollConfigController controller = new EmployeePayrollConfigController(service, userContext, remunerationSimulation);
        CalculateNetResponse result = controller.calculateNet(10L, req);

        assertEquals(new BigDecimal("3820.500"), result.netInHand());
    }
}
