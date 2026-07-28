package com.szsemicon.hr.authorization.domain;

public final class CapabilityCodes {

    public static final String MASTER_DATA_READ = "MASTER_DATA:READ";
    public static final String AUDIT_READ = "AUDIT:READ";
    public static final String OPERATIONS_READ = "OPERATIONS:READ";
    public static final String PEOPLE_IMPORT_TEMPLATE_DOWNLOAD =
            "PEOPLE_IMPORT:TEMPLATE_DOWNLOAD";
    public static final String PEOPLE_IMPORT_READ = "PEOPLE_IMPORT:READ";
    public static final String PEOPLE_IMPORT_CREATE = "PEOPLE_IMPORT:CREATE";
    public static final String PEOPLE_IMPORT_UPLOAD = "PEOPLE_IMPORT:UPLOAD";
    public static final String PEOPLE_IMPORT_MAP = "PEOPLE_IMPORT:MAP";
    public static final String PEOPLE_IMPORT_PRECHECK = "PEOPLE_IMPORT:PRECHECK";
    public static final String PEOPLE_IMPORT_ERROR_REPORT_DOWNLOAD =
            "PEOPLE_IMPORT:ERROR_REPORT_DOWNLOAD";
    public static final String PEOPLE_IMPORT_PUBLISH = "PEOPLE_IMPORT:PUBLISH";
    public static final String PEOPLE_IMPORT_VOID = "PEOPLE_IMPORT:VOID";
    public static final String PEOPLE_IMPORT_ROLLBACK = "PEOPLE_IMPORT:ROLLBACK";
    public static final String ORGANIZATION_READ = "ORGANIZATION:READ";
    public static final String ORGANIZATION_CREATE = "ORGANIZATION:CREATE";
    public static final String ORGANIZATION_EDIT = "ORGANIZATION:EDIT";
    public static final String EMPLOYEE_READ = "EMPLOYEE:READ";
    public static final String EMPLOYEE_CREATE = "EMPLOYEE:CREATE";
    public static final String EMPLOYEE_EDIT = "EMPLOYEE:EDIT";
    public static final String EMPLOYMENT_READ = "EMPLOYMENT:READ";
    public static final String EMPLOYMENT_CREATE = "EMPLOYMENT:CREATE";
    public static final String EMPLOYMENT_EDIT = "EMPLOYMENT:EDIT";
    public static final String PRIOR_SERVICE_READ = "PRIOR_SERVICE:READ";
    public static final String PRIOR_SERVICE_ADJUST = "PRIOR_SERVICE:ADJUST";
    public static final String ATTENDANCE_SETUP_READ = "ATTENDANCE_SETUP:READ";
    public static final String ATTENDANCE_SETUP_MANAGE_GROUP =
            "ATTENDANCE_SETUP:MANAGE_GROUP";
    public static final String ATTENDANCE_SETUP_ASSIGN = "ATTENDANCE_SETUP:ASSIGN";
    public static final String ATTENDANCE_SETUP_MANAGE_SHIFT =
            "ATTENDANCE_SETUP:MANAGE_SHIFT";
    public static final String ATTENDANCE_SETUP_MANAGE_CALENDAR =
            "ATTENDANCE_SETUP:MANAGE_CALENDAR";
    public static final String ATTENDANCE_SETUP_MANAGE_POLICY =
            "ATTENDANCE_SETUP:MANAGE_POLICY";

    private CapabilityCodes() {
    }
}
