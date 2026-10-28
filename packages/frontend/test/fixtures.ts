import type {
  AnalystCase,
  CasePage,
  CaseSummary,
  CategoryTuple,
  CfpbCase,
  ImportReport,
  SearchMatch,
  SearchResponse,
} from '@finresolve/api-client';

export const categoriesFixture: CategoryTuple[] = [
  {
    product: 'Checking or savings account',
    subProduct: 'Checking account',
    issue: 'Managing an account',
    subIssue: 'Deposits and withdrawals',
  },
  {
    product: 'Checking or savings account',
    subProduct: 'Checking account',
    issue: 'Managing an account',
    subIssue: null,
  },
  {
    product: 'Checking or savings account',
    subProduct: 'Savings account',
    issue: 'Problem with a loan',
    subIssue: null,
  },
  {
    product: 'Money transfer, virtual currency, or money service',
    subProduct: 'Domestic money transfer',
    issue: 'Money was not available when promised',
    subIssue: null,
  },
];

export const cfpbId = '11111111-1111-4111-8111-111111111111';
export const analystId = '22222222-2222-4222-8222-222222222222';

export const cfpbSummary: CaseSummary = {
  id: cfpbId,
  source: 'CFPB',
  product: 'Checking or savings account',
  subProduct: 'Checking account',
  issue: 'Managing an account',
  subIssue: 'Deposits and withdrawals',
  receivedDate: '2024-11-02',
  company: 'Example Bank',
  companyResponse: 'Closed with explanation',
  createdAt: '2026-10-26T10:00:00Z',
  updatedAt: '2026-10-26T10:00:00Z',
};

export const analystSummary: CaseSummary = {
  id: analystId,
  source: 'ANALYST',
  product: 'Money transfer, virtual currency, or money service',
  subProduct: 'Domestic money transfer',
  issue: 'Money was not available when promised',
  subIssue: null,
  receivedDate: null,
  company: 'Example Credit Union',
  companyResponse: 'Funds were released after a trace.',
  createdAt: '2026-10-27T10:00:00Z',
  updatedAt: '2026-10-27T11:00:00Z',
};

export const cfpbCase: CfpbCase = {
  ...cfpbSummary,
  source: 'CFPB',
  narrative: 'Money left my bank balance twice even though I only authorized one payment.',
  provenance: {
    complaintId: '1234567',
    importBatchId: '33333333-3333-4333-8333-333333333333',
    archiveUrl: 'https://example.gov/archive.zip',
    sourceFileName: 'sample.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
    csvSha256: 'abc123',
  },
  analystNotes: null,
  referenceCaseIds: [],
};

export const analystCase: AnalystCase = {
  ...analystSummary,
  source: 'ANALYST',
  narrative: 'A domestic transfer was delayed beyond the promised delivery date.',
  provenance: null,
  analystNotes: {
    handlingNotes: 'Requested a trace and confirmed the receiving bank had the funds.',
    outcomeAssessment: 'PARTIALLY_RESOLVED',
    evaluationNotes: 'Consumer received the transfer after a delay.',
  },
  referenceCaseIds: new Set([cfpbId]),
};

export const matchFixture: SearchMatch = {
  _case: cfpbCase,
  score: 0.812,
  categoryScore: 0.75,
  textScore: 0.85,
  categoryExplanation: [
    {
      field: 'product',
      queryValue: 'Checking or savings account',
      caseValue: 'Checking or savings account',
      status: 'MATCH',
      configuredWeight: 0.35,
      effectiveWeight: 0.35,
      contribution: 0.35,
    },
    {
      field: 'subProduct',
      queryValue: 'Checking account',
      caseValue: 'Checking account',
      status: 'MATCH',
      configuredWeight: 0.15,
      effectiveWeight: 0.15,
      contribution: 0.15,
    },
    {
      field: 'issue',
      queryValue: 'Managing an account',
      caseValue: 'Managing an account',
      status: 'MATCH',
      configuredWeight: 0.35,
      effectiveWeight: 0.35,
      contribution: 0.35,
    },
    {
      field: 'subIssue',
      queryValue: 'Deposits and withdrawals',
      caseValue: null,
      status: 'MISSING_IN_CASE',
      configuredWeight: 0.15,
      effectiveWeight: 0.15,
      contribution: 0,
    },
  ],
  sharedTerms: [
    { term: 'transfer', contribution: 0.2 },
    { term: 'payment', contribution: 0.12 },
  ],
};

export function searchResponse(
  status: SearchResponse['status'] = 'MATCHES',
): SearchResponse {
  return {
    status,
    message: 'Synthetic fixture.',
    results: status === 'MATCHES' ? [matchFixture] : [],
    returnedCount: status === 'MATCHES' ? 1 : 0,
    limit: 5,
    corpusSize: status === 'EMPTY_KNOWLEDGE_BASE' ? 0 : 500,
    _configuration: {
      categoryWeight: 0.4,
      textWeight: 0.6,
      categoryFieldWeights: {
        product: 0.35,
        subProduct: 0.15,
        issue: 0.35,
        subIssue: 0.15,
      },
      threshold: 0.5,
      requiresTextOverlap: true,
      algorithmVersion: 'tfidf-cosine-v1',
    },
    warnings: [
      'Similarity calibration has not been validated by human review.',
    ],
  };
}

export function casePage(
  items: CaseSummary[],
  page = 0,
  totalPages = 3,
  hasNext = true,
): CasePage {
  return {
    items,
    page,
    size: 20,
    totalElements: 41,
    totalPages,
    hasNext,
  };
}

export const importReport: ImportReport = {
  batchId: '44444444-4444-4444-8444-444444444444',
  replayed: false,
  metadata: {
    archiveUrl: 'https://example.gov/archive.zip',
    sourceFileName: 'sample.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
  },
  csvSha256: 'deadbeef',
  processed: 3,
  imported: 1,
  duplicates: 1,
  rejected: 1,
  errors: [
    {
      recordNumber: 2,
      field: 'Consumer complaint narrative',
      code: 'INVALID_FORMAT',
      message: 'ignored backend text',
    },
  ],
  errorsTruncated: false,
  importedAt: '2026-10-28T10:00:00Z',
};

export function makeClient(overrides: Record<string, unknown> = {}) {
  return {
    categories: {
      getCategories: async () => ({ items: categoriesFixture, scope: 'IMPORTED_CFPB_SAMPLE' }),
    },
    search: {
      searchCases: async () => searchResponse('MATCHES'),
    },
    cases: {
      listCases: async () => casePage([cfpbSummary]),
      getCase: async () => cfpbCase,
      retainCase: async () => analystCase,
      replaceAnalystCase: async () => analystCase,
    },
    imports: {
      importCfpb: async () => importReport,
    },
    ...overrides,
  };
}
