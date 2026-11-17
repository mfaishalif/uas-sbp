import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CaseLibraryPage } from '@/components/cases/CaseLibraryPage';
import { AppProviders } from '@/contexts/AppProviders';
import {
  analystCase,
  analystId,
  analystSummary,
  casePage,
  cfpbCase,
  cfpbId,
  cfpbSummary,
  makeClient,
} from './fixtures';

const { getClientMock, listCasesMock, getCaseMock } = vi.hoisted(() => ({
  getClientMock: vi.fn(),
  listCasesMock: vi.fn(),
  getCaseMock: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  getApiClient: () => getClientMock(),
}));

function setup() {
  getClientMock.mockReturnValue(
    makeClient({ cases: { listCases: listCasesMock, getCase: getCaseMock } }),
  );
  render(
    <AppProviders>
      <CaseLibraryPage />
    </AppProviders>,
  );
}

describe('case library', () => {
  it('requests 20 cases for the chosen source and paginates', async () => {
    const user = userEvent.setup();
    listCasesMock.mockImplementation(async ({ page }) => casePage([cfpbSummary], page, 3, page < 2));
    setup();

    await screen.findByRole('button', { name: /Checking or savings account/ });
    expect(listCasesMock).toHaveBeenNthCalledWith(1, { page: 0, size: 20, source: undefined });
    expect(screen.getByText(/41 cases/)).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'Analyst' }));
    await waitFor(() => {
      expect(listCasesMock).toHaveBeenCalledWith({ page: 0, size: 20, source: 'ANALYST' });
    });

    await user.click(screen.getByRole('radio', { name: 'CFPB' }));
    await user.click(await screen.findByRole('button', { name: 'Next' }));
    await waitFor(() => {
      expect(listCasesMock).toHaveBeenCalledWith({ page: 1, size: 20, source: 'CFPB' });
    });
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();
  });

  it('filters the current page by text and shows source badges', async () => {
    const user = userEvent.setup();
    listCasesMock.mockResolvedValue(casePage([cfpbSummary, analystSummary], 0, 1, false));
    setup();

    await screen.findByRole('button', { name: /Money transfer/ });
    const [cfpbCard, analystCard] = screen.getAllByRole('article');
    expect(within(cfpbCard).getByText('CFPB')).toBeInTheDocument();
    expect(within(analystCard).getByText('Analyst')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Filter this page'), 'credit union');
    expect(screen.getByText('1 of 2 cases on this page match “credit union”.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Checking or savings account/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Money transfer/ })).toBeInTheDocument();

    await user.clear(screen.getByLabelText('Filter this page'));
    await user.type(screen.getByLabelText('Filter this page'), 'zzz');
    expect(screen.getByText('No cases on this page match')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filter' }));
    expect(screen.getAllByRole('article')).toHaveLength(2);
  });

  it('opens a detail modal, follows a reference in place, and returns focus on Escape', async () => {
    const user = userEvent.setup();
    listCasesMock.mockResolvedValue(casePage([analystSummary], 0, 1, false));
    getCaseMock.mockImplementation(async ({ id }: { id: string }) => (
      id === analystId ? analystCase : cfpbCase
    ));
    setup();

    const trigger = await screen.findByRole('button', { name: /Money transfer/ });
    await user.click(trigger);

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(analystCase.narrative)).toBeInTheDocument();
    expect(within(dialog).getByText(analystCase.analystNotes.handlingNotes)).toBeInTheDocument();
    expect(within(dialog).getByText('Example Credit Union')).toBeInTheDocument();
    expect(within(dialog).getByRole('heading', { name: 'References' })).toBeInTheDocument();

    const reference = await within(dialog).findByRole('button', { name: /Checking or savings account/ });
    await user.click(reference);

    expect(await within(dialog).findByText(cfpbCase.narrative)).toBeInTheDocument();
    expect(getCaseMock).toHaveBeenCalledWith({ id: cfpbId });
    expect(within(dialog).queryByRole('heading', { name: 'Handling notes' })).not.toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: /Back to previous case/ }));
    expect(await within(dialog).findByText(analystCase.narrative)).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });
});
