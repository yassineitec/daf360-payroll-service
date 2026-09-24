package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetRequest;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollConfigService;
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

    @Test
    void calculateNet_delegatesToServiceAndReturnsNetInHand() {
        when(service.calculateNet(10L, new BigDecimal("5000.000")))
                .thenReturn(new CalculateNetResponse(new BigDecimal("3820.500")));

        EmployeePayrollConfigController controller = new EmployeePayrollConfigController(service, userContext);
        CalculateNetResponse result = controller.calculateNet(10L, new CalculateNetRequest(new BigDecimal("5000.000")));

        assertEquals(new BigDecimal("3820.500"), result.netInHand());
    }
}
