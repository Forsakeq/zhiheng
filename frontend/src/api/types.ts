export type Id = string
export type RecordType = 'SLEEP' | 'ACTIVITY' | 'MEAL' | 'ENERGY'
export type TaskStatus = 'DRAFT' | 'PENDING' | 'COMPLETED' | 'SKIPPED' | 'CANCELLED'

export interface PageResult<T> {
  items: T[]
  nextCursor: string | null
  hasMore: boolean
  totalApproximate?: number | null
}

export interface MutationResult {
  resourceType: string
  id: Id
  rowVersion: string
  changeSeq: string | null
  operationId: string
  replayed: boolean
  [key: string]: unknown
}

export interface AuthTokens {
  accessToken: string
  accessExpiresAt: string
  refreshToken: string
  refreshExpiresAt: string
  sessionId: Id
  sessionFamilyId: string
  generation: string
  audience: string
  installationId: Id
  userId: Id
  datasetEpoch: string
  securityVersion: string
  tokenType: string
}

export interface UserProfile {
  id: Id
  rowVersion: string
  nickname: string
  avatarFileId: Id | null
  bio: string | null
  timeZone: string
  weekStartsOn: number
  onboardingStep: string
  updatedAt: string
}

export interface UserPreferences {
  id: Id
  rowVersion: string
  theme: 'LIGHT' | 'DARK' | 'SYSTEM'
  reduceMotion: boolean
  fontScale: number
  activityPreferences: string[]
  excludedActivityTypes: string[]
  dailyTaskLimit: number
  defaultTaskMinutes: number
  preferredSessionMinutes: number | null
  preferredWakeTime: string | null
  preferredBedTime: string | null
  planTone: string
  quietHoursEnabled: boolean
  quietStart: string | null
  quietEnd: string | null
}

export interface ConsentView {
  id: Id
  rowVersion: string
  purpose: string
  status: string
  policyVersion: string
  decidedAt: string
}

export interface PolicyVersion {
  purpose: string
  version: string
  title: string
  documentUrl: string
}

export interface MeView {
  userId: Id
  accountMasked: string
  status: string
  profile: UserProfile
  preferences: UserPreferences
  consents: ConsentView[]
  datasetEpoch: string
  committedSeq: string
  dataWriteFence: boolean
  createdAt: string
}

export interface ClientConfiguration {
  apiVersion: string
  minimumAppVersion: string
  serverTime: string
  localDevelopment?: boolean
  authentication?: {
    registrationEnabled: boolean
    verificationDelivery: 'CONSOLE' | 'SMTP'
  }
  policyVersions: PolicyVersion[]
  features: {
    code: string
    enabled: boolean
    requiresConsent: string | null
    availabilityReason: string | null
  }[]
  supportedRecordTypes: RecordType[]
  supportedSyncOperationCodes: string[]
}

export interface TaskView {
  id: Id
  rowVersion: string
  planId: Id
  currentPlanVersionId: Id
  ruleId: Id | null
  occurrenceKey: string | null
  title: string
  taskType: string
  scheduledStartAt: string
  scheduledEndAt: string
  taskLocalDate: string
  taskTimeZone: string
  plannedDurationMinutes: number
  isFixed: boolean
  status: TaskStatus
  completedAt: string | null
  skippedAt: string | null
  sourceType: string
  updatedAt: string
}

export interface RecordView {
  id: Id
  rowVersion: string
  clientRecordId: string
  recordType: RecordType
  startedAt: string
  endedAt: string | null
  recordLocalDate: string
  recordTimeZone: string
  utcOffsetMinutes: number
  sourceType: string
  sourceConnectionId: Id | null
  qualityStatus: string
  invalidReason: string | null
  notes: string | null
  detail: object
  tagIds: Id[]
  attachments: {
    id: Id
    parentRecordRowVersion: string
    fileId: Id
    caption: string | null
    sortOrder: number
    mediaType: string
    sizeBytes: number
  }[]
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface UserGoalView {
  id: Id
  rowVersion: string
  definitionId: Id
  isCustom: boolean
  title: string
  targetValue: number | null
  unit: string | null
  startDate: string
  endDate: string | null
  timeZone: string
  priority: number
  note: string | null
  status: string
  completedAt: string | null
}

export interface HomeView {
  date: string
  displayTimeZone: string
  taskSummary: {
    pending: number
    completed: number
    skipped: number
    cancelled: number
    executionDenominator: number
    completionRate: number | null
    metricDefinitionVersion: string
  }
  nextTask: TaskView | null
  recentRecords: RecordView[]
  recordCoverage: unknown
  reminderCapability: string
  committedSeq: string
  datasetEpoch: string
  statsStale: boolean
}

export interface DownloadView {
  url: string
  expiresAt: string
  fileName: string
  mediaType: string
  sizeBytes: number
}
