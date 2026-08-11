package com.daf360.payroll.security;

import java.util.Set;

/**
 * Canonical list of all PAYROLL_* permission codes.
 * Must stay in sync with the PermissionCatalog in daf360-rh-service.
 */
public final class PermissionCatalog {

    private PermissionCatalog() {}

    public static final String RUN_SIMULATION              = "PAYROLL_RUN_SIMULATION";
    public static final String VIEW_INDIVIDUAL             = "PAYROLL_VIEW_INDIVIDUAL";
    public static final String APPROVE_PARAMSET            = "PAYROLL_APPROVE_PARAMSET";
    public static final String RUN_CALIBRATION             = "PAYROLL_RUN_CALIBRATION";
    public static final String EXPORT_BUDGET               = "PAYROLL_EXPORT_BUDGET";
    public static final String IMPORT_PARTNER              = "PAYROLL_IMPORT_PARTNER";
    public static final String VIEW_AGGREGATE              = "PAYROLL_VIEW_AGGREGATE";
    public static final String APPROVE_PARAMSET_FAST_TRACK = "PAYROLL_APPROVE_PARAMSET_FAST_TRACK";
    public static final String VIEW_PARAMSET               = "PAYROLL_VIEW_PARAMSET";
    public static final String UPLOAD_ACTUAL               = "PAYROLL_UPLOAD_ACTUAL";
    public static final String SUPER_ADMIN                 = "PAYROLL_SUPER_ADMIN";
    public static final String RUN_ENGINE                  = "PAYROLL_RUN_ENGINE";
    public static final String VIEW_RESULTS                = "PAYROLL_VIEW_RESULTS";
    public static final String MANAGE_RUBRIQUES            = "PAYROLL_MANAGE_RUBRIQUES";
    public static final String MANAGE_COUNTRIES            = "PAYROLL_MANAGE_COUNTRIES";
    public static final String IMPORT_CALIBRATION          = "PAYROLL_IMPORT_CALIBRATION";
    /** Country Director role: read-only view of aggregate budget/forecast data, no individual salary access. */
    public static final String VIEW_BUDGET_AGGREGATE       = "PAYROLL_VIEW_BUDGET_AGGREGATE";

    /** All codes — used to validate PermissionCatalog entries in RH service. */
    public static final Set<String> ALL_CODES = Set.of(
            RUN_SIMULATION, VIEW_INDIVIDUAL, APPROVE_PARAMSET, RUN_CALIBRATION,
            EXPORT_BUDGET, IMPORT_PARTNER, VIEW_AGGREGATE, APPROVE_PARAMSET_FAST_TRACK,
            VIEW_PARAMSET, UPLOAD_ACTUAL, SUPER_ADMIN,
            RUN_ENGINE, VIEW_RESULTS, MANAGE_RUBRIQUES, MANAGE_COUNTRIES,
            IMPORT_CALIBRATION, VIEW_BUDGET_AGGREGATE
    );

    /** Permissions that bypass pays isolation. */
    public static final Set<String> ADMIN_PERMISSIONS = Set.of(SUPER_ADMIN);
}
