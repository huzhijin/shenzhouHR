export type LifecycleStatus = 'ACTIVE' | 'INACTIVE';
export type VersionStatus = 'DRAFT' | 'PUBLISHED' | 'INACTIVE';
export type CalendarStatus = 'DRAFT' | 'PUBLISHED' | 'INACTIVE';
export type PolicyVersionStatus = 'DRAFT' | 'VALIDATED' | 'PUBLISHED';
export type PolicySimulationStatus =
  | 'MATCHED'
  | 'NOT_MATCHED'
  | 'ON_TIME'
  | 'EXEMPTED'
  | 'LATE';
export type SegmentType = 'WORK' | 'BREAK' | 'MEAL';
export type CalendarDayType = 'WORKDAY' | 'WEEKEND' | 'PUBLIC_HOLIDAY' | 'SPECIAL_WORKDAY';
export type AttendancePolicyKind =
  | 'MEAL_DEDUCTION'
  | 'LATE_GRACE'
  | 'MONTHLY_LATE_EXEMPTION';

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface LocationView {
  locationId: string;
  legalEntityId: string;
  code: string;
  locationRevisionId: string;
  revisionNumber: number;
  name: string;
  timeZone: string;
  status: LifecycleStatus;
  effectiveFrom: string;
  effectiveTo?: string | null;
  snapshotDigest: string;
  rowVersion: number;
  changeReason: string;
  updatedAt: string;
}

export interface LocationInput {
  legalEntityId: string;
  code: string;
  name: string;
  timeZone: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface AttendanceGroupView {
  groupId: string;
  legalEntityId: string;
  code: string;
  groupRevisionId: string;
  revisionNumber: number;
  name: string;
  locationId: string;
  locationRevisionId: string;
  calendarId: string;
  shiftTemplateId: string;
  status: LifecycleStatus;
  effectiveFrom: string;
  effectiveTo?: string | null;
  snapshotDigest: string;
  rowVersion: number;
  changeReason: string;
  updatedAt: string;
}

export interface AttendanceGroupInput {
  legalEntityId: string;
  code: string;
  name: string;
  locationId: string;
  calendarId: string;
  shiftTemplateId: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface AssignmentView {
  assignmentId: string;
  groupId: string;
  employeeId: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  rowVersion: number;
  monthlyContextKey: string;
  changeReason: string;
  updatedAt: string;
}

export interface AssignmentInput {
  employeeId: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface ShiftTemplateView {
  shiftId: string;
  legalEntityId: string;
  locationId: string;
  code: string;
  name: string;
  status: LifecycleStatus;
  rowVersion: number;
  changeReason: string;
  updatedAt: string;
}

export interface ShiftTemplateInput {
  legalEntityId: string;
  locationId: string;
  code: string;
  name: string;
  reason: string;
}

export interface ShiftSegment {
  segmentType: SegmentType;
  startLocalTime: string;
  startDayOffset: 0 | 1;
  endLocalTime: string;
  endDayOffset: 0 | 1;
}

export interface ShiftVersionView {
  shiftVersionId: string;
  shiftId: string;
  versionNumber: number;
  status: VersionStatus;
  effectiveFrom: string;
  effectiveTo?: string | null;
  timeZone: string;
  segments: ShiftSegment[];
  snapshotDigest: string;
  rowVersion: number;
  changeReason: string;
  publishedAt?: string | null;
  updatedAt: string;
}

export interface ShiftVersionInput {
  effectiveFrom: string;
  effectiveTo?: string | null;
  segments: ShiftSegment[];
  reason: string;
}

export interface WorkCalendarView {
  calendarId: string;
  legalEntityId: string;
  locationId: string;
  code: string;
  calendarVersionId: string;
  versionNumber: number;
  name: string;
  calendarYear: number;
  timeZone: string;
  status: CalendarStatus;
  effectiveFrom: string;
  effectiveTo: string;
  snapshotDigest: string;
  rowVersion: number;
  changeReason: string;
  updatedAt: string;
}

export interface WorkCalendarInput {
  legalEntityId: string;
  locationId: string;
  code: string;
  name: string;
  calendarYear: number;
  timeZone: string;
  effectiveFrom: string;
  effectiveTo: string;
  reason: string;
}

export interface WorkCalendarVersionInput {
  name: string;
  calendarYear: number;
  timeZone: string;
  effectiveFrom: string;
  effectiveTo: string;
  reason: string;
}

export interface CalendarDayView {
  calendarDayId: string;
  calendarId: string;
  calendarVersionId: string;
  businessDate: string;
  dayType: CalendarDayType;
  shiftVersionOverrideId?: string | null;
  rowVersion: number;
  changeReason: string;
}

export interface CalendarDayInput {
  businessDate: string;
  dayType: CalendarDayType;
  shiftVersionOverrideId?: string | null;
}

export interface PolicyFieldDefinition {
  key: string;
  label: string;
  valueType: string;
  required: boolean;
}

export interface PolicyTemplateDefinition {
  templateId: string;
  policyKind: AttendancePolicyKind;
  name: string;
  fields: PolicyFieldDefinition[];
}

export interface PolicyBindingView {
  bindingId: string;
  bindingRevisionId: string;
  revisionNumber: number;
  legalEntityId: string;
  policyKind: AttendancePolicyKind;
  policyVersionId: string;
  groupId: string;
  groupRevisionId: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  status: LifecycleStatus;
  snapshotDigest: string;
  rowVersion: number;
  changeReason: string;
  updatedAt: string;
}

export interface PolicyBindingPreviewInput {
  policyKind: AttendancePolicyKind;
  policyVersionId: string;
  groupId: string;
  groupRevisionId: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface PolicyBindingInput extends PolicyBindingPreviewInput {
  impactToken: string;
}

export interface PolicySimulationInput {
  employeeId: string;
  businessDate: string;
  correctionAsOf: string;
  punches: Array<{
    direction: 'ENTRY' | 'EXIT';
    instant: string;
    workSegmentId?: string | null;
    association?: string | null;
  }>;
}

export interface PolicySimulationView {
  policyKind: AttendancePolicyKind;
  status: PolicySimulationStatus;
  policyVersionId: string;
  configurationDigest: string;
  matched: boolean;
  consumesAllowance: boolean;
  rawLateMinutes?: number | null;
  predictedMonthlyConsumption: number;
  usageProvenance: string;
  usageKnowledgeTime: string;
  deductionMinutes?: number | null;
  correctionDeadline?: string | null;
  affectedSegment?: string | null;
  explanation: string;
  writesFormalResult: boolean;
}

export interface PolicySimulationBatchView {
  configurationDigest: string;
  results: PolicySimulationView[];
}

export interface PolicyParameterValue {
  key: string;
  value: unknown;
}

export interface PolicyValidationIssue {
  code: string;
  field: string;
  message: string;
}

export interface PolicyValidationResult {
  valid: boolean;
  issues: PolicyValidationIssue[];
  validatedAt?: string | null;
}

export interface AttendancePolicyVersionSummary {
  scopedVersionId: string;
  scopeId: string;
  templateId: string;
  legalEntityId: string;
  policyKind: AttendancePolicyKind;
  versionNumber: number;
  status: PolicyVersionStatus;
  parameters: PolicyParameterValue[];
  effectiveFrom: string;
  effectiveTo: string | null;
  changeReason: string;
  validation: PolicyValidationResult;
  snapshotDigest: string | null;
  rollbackOfScopedVersionId: string | null;
  createdBy: string;
  createdAt: string;
  publishedAt: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
  deactivationEffectiveFrom: string | null;
  rowVersion: number;
}

export type AttendancePolicyVersionView = AttendancePolicyVersionSummary;

export interface AttendancePolicyDraftInput {
  basedOnVersionId?: string | null;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface AttendancePolicyDraftUpdateInput {
  parameters: PolicyParameterValue[];
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface PolicyImpactView {
  groupCount: number;
  assignmentCount: number;
  countSource: string;
  impactToken: string;
  expiresAt: string;
}

export interface AttendanceConfigurationView {
  status: string;
  employeeId: string;
  businessDate: string;
  groupId?: string | null;
  groupRevisionId?: string | null;
  locationRevisionId?: string | null;
  calendarVersionId?: string | null;
  calendarDay?: CalendarDayView | null;
  shiftVersion?: ShiftVersionView | null;
  policyBindings: PolicyBindingView[];
  configurationDigest?: string | null;
  monthlyContextKey?: string | null;
  explanation: string;
}
