import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EditCasePage } from '@/components/cases/EditCasePage';
import { AppProviders } from '@/contexts/AppProviders';
import {
  analystCase,
  analystId,
  cfpbCase,
  cfpbId,
  makeClient,
} from './fixtures';

const { getClientMock, getCaseMock, replaceMock, pushMock } = vi.hoisted(() => ({
  getClientMock: vi.fn(),
  getCaseMock: vi.fn(),
  replaceMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  getApiClient: () => getClientMock(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/cases',
}));

vi.mock('next/link', () => ({
  default: ({ href, children, ...props }: { href: string; children: ReactNode }) => (
    <a href={href} {...props}>{children}</a>
  ),
}));

describe('analyst case editing', () => {
  it('refuses to render an edit form for a CFPB archive case', async () => {
    getCaseMock.mockResolvedValue(cfpbCase);
    replaceMock.mockResolvedValue(cfpbCase);
    getClientMock.mockReturnValue(
      makeClient({
        cases: {
          getCase: getCaseMock,
          replaceAnalystCase: replaceMock,
          listCases: vi.fn(),
        },
      }),
    );

    render(
      <AppProviders>
        <EditCasePage id={cfpbId} />
      </AppProviders>,
    );

    expect(
      await screen.findByRole('heading', { name: 'This CFPB case cannot be edited' }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Handling notes')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
    expect(replaceMock).not.toHaveBeenCalled();
  });

  it('loads all detail first and sends the complete replacement payload', async () => {
    const user = userEvent.setup();
    getCaseMock.mockImplementation(async ({ id }: { id: string }) => (
      id === analystId ? analystCase : cfpbCase
    ));
    replaceMock.mockResolvedValue({
      ...analystCase,
      analystNotes: {
        ...analystCase.analystNotes,
        evaluationNotes: 'Updated evaluation.',
      },
    });
    getClientMock.mockReturnValue(
      makeClient({
        cases: {
          getCase: getCaseMock,
          replaceAnalystCase: replaceMock,
          listCases: vi.fn(),
        },
      }),
    );

    render(
      <AppProviders>
        <EditCasePage id={analystId} />
      </AppProviders>,
    );

    const save = await screen.findByRole('button', { name: 'Save changes' });
    await screen.findByRole('option', { name: 'Domestic money transfer' });
    expect(getCaseMock).toHaveBeenCalledWith({ id: analystId });

    await user.click(save);

    await waitFor(() => expect(replaceMock).toHaveBeenCalledTimes(1));
    const call = replaceMock.mock.calls[0][0];
    expect(call.id).toBe(analystId);
    expect(call.retainCaseRequest).toEqual({
      product: 'Money transfer, virtual currency, or money service',
      subProduct: 'Domestic money transfer',
      issue: 'Money was not available when promised',
      subIssue: null,
      narrative: 'A domestic transfer was delayed beyond the promised delivery date.',
      receivedDate: null,
      company: 'Example Credit Union',
      companyResponse: 'Funds were released after a trace.',
      handlingNotes: 'Requested a trace and confirmed the receiving bank had the funds.',
      outcomeAssessment: 'PARTIALLY_RESOLVED',
      evaluationNotes: 'Consumer received the transfer after a delay.',
      referenceCaseIds: new Set([cfpbId]),
    });
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith(`/cases/${analystId}`));
  });
});
