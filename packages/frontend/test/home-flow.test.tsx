import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiClientError } from '@finresolve/api-client';
import { PrecedentWorkspace } from '@/components/home/PrecedentWorkspace';
import { ScoringExplainer } from '@/components/home/ScoringExplainer';
import { AppProviders } from '@/contexts/AppProviders';
import {
  analystCase,
  cfpbId,
  makeClient,
  searchResponse,
  secondCfpbId,
  thirdCfpbId,
  threeMatches,
} from './fixtures';

const { getClientMock, searchMock, retainMock } = vi.hoisted(() => ({
  getClientMock: vi.fn(),
  searchMock: vi.fn(),
  retainMock: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  getApiClient: () => getClientMock(),
}));

const NARRATIVE = 'Money left my bank balance twice even though I authorized one payment.';

function precedentCards() {
  return within(screen.getByRole('region', { name: 'Closest precedents' })).queryAllByRole('article');
}

async function findPrecedentCards() {
  await waitFor(() => expect(precedentCards().length).toBeGreaterThan(0));
  return precedentCards();
}

function setup() {
  const base = makeClient();
  getClientMock.mockReturnValue(
    makeClient({
      search: { searchCases: searchMock },
      cases: { ...base.cases, retainCase: retainMock },
    }),
  );
  render(
    <AppProviders>
      <PrecedentWorkspace />
      <ScoringExplainer />
    </AppProviders>,
  );
}

async function fillAndSearch(user: ReturnType<typeof userEvent.setup>, narrative = NARRATIVE) {
  await screen.findByRole('option', { name: 'Checking or savings account' });
  await user.selectOptions(screen.getByLabelText('Product'), 'Checking or savings account');
  await user.selectOptions(screen.getByLabelText('Issue'), 'Managing an account');
  fireEvent.change(screen.getByLabelText('Complaint narrative'), { target: { value: narrative } });
  await user.click(screen.getByRole('button', { name: 'Find precedents' }));
}

describe('landing page: search → recommendation → save', () => {
  it('shows three precedents, recommends by weighted vote, and saves the chosen response', async () => {
    const user = userEvent.setup();
    searchMock.mockResolvedValue(searchResponse('MATCHES', threeMatches));
    let resolveSave: (value: unknown) => void = () => {};
    retainMock.mockImplementation(
      () => new Promise((resolve) => {
        resolveSave = resolve;
      }),
    );
    setup();

    expect(screen.getByText(/Showing the default configuration/)).toBeInTheDocument();

    await fillAndSearch(user);

    expect(await findPrecedentCards()).toHaveLength(3);
    expect(searchMock).toHaveBeenCalledWith({
      searchRequest: {
        product: 'Checking or savings account',
        subProduct: null,
        issue: 'Managing an account',
        subIssue: null,
        narrative: NARRATIVE,
        limit: 3,
      },
    });

    // Score breakdown, field status, shared terms, company response on each card.
    const first = precedentCards()[0];
    expect(within(first).getByText('81.0%')).toBeInTheDocument();
    expect(within(first).getByText('Missing in case')).toBeInTheDocument();
    expect(within(first).getByText('transfer')).toBeInTheDocument();
    expect(within(first).getByText('Closed with explanation')).toBeInTheDocument();

    // (0.81 + 0.55) / 1.98 = 68.7%; 0.62 / 1.98 = 31.3%.
    const panel = screen.getByRole('region', { name: 'Response recommendation' });
    expect(within(panel).getByText('68.7%')).toBeInTheDocument();
    expect(within(panel).getByText('31.3%')).toBeInTheDocument();
    expect(within(panel).getByText('0.0%')).toBeInTheDocument();
    expect(within(panel).getAllByText('Recommended')).toHaveLength(1);

    const responseSelect = screen.getByLabelText('Company response') as HTMLSelectElement;
    expect(responseSelect.value).toBe('Closed with explanation');
    expect(
      screen.getByRole('option', { name: 'Closed with explanation (Recommended)' }),
    ).toBeInTheDocument();
    expect(screen.getByText(/Live configuration from your last search/)).toBeInTheDocument();

    await user.selectOptions(responseSelect, 'Closed with monetary relief');
    await user.type(screen.getByLabelText('Handling notes'), 'Refunded the duplicate charge.');

    // Two submits before the first request settles send only one request.
    const form = screen.getByRole('button', { name: 'Save case' }).closest('form')!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(retainMock).toHaveBeenCalledTimes(1);
    expect(retainMock).toHaveBeenCalledWith({
      retainCaseRequest: {
        product: 'Checking or savings account',
        subProduct: null,
        issue: 'Managing an account',
        subIssue: null,
        narrative: NARRATIVE,
        receivedDate: null,
        company: null,
        companyResponse: 'Closed with monetary relief',
        handlingNotes: 'Refunded the duplicate charge.',
        outcomeAssessment: 'UNKNOWN',
        evaluationNotes: null,
        referenceCaseIds: new Set([cfpbId, secondCfpbId, thirdCfpbId]),
      },
    });

    resolveSave({
      ...analystCase,
      referenceCaseIds: new Set([cfpbId, secondCfpbId, thirdCfpbId]),
    });

    expect(await screen.findByText('Case saved to the library')).toBeInTheDocument();
    expect(screen.getByText(/with 3 references/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open the case library' })).toHaveAttribute('href', '/cases');

    // The form is reset and the results are cleared.
    expect((screen.getByLabelText('Product') as HTMLSelectElement).value).toBe('');
    expect((screen.getByLabelText('Complaint narrative') as HTMLTextAreaElement).value).toBe('');
    expect(precedentCards()).toHaveLength(0);
    expect(screen.queryByLabelText('Handling notes')).not.toBeInTheDocument();
  });

  it('shows API field errors on the matching fields', async () => {
    const user = userEvent.setup();
    searchMock.mockResolvedValue(searchResponse('MATCHES', threeMatches));
    retainMock.mockRejectedValue(
      new ApiClientError(400, 'VALIDATION_ERROR', 'Invalid', [
        { field: 'handlingNotes', code: 'TOO_LONG', message: 'backend text' },
        { field: 'narrative', code: 'NO_MEANINGFUL_TOKENS', message: 'backend text' },
      ]),
    );
    setup();

    await fillAndSearch(user);
    await findPrecedentCards();
    await user.type(screen.getByLabelText('Handling notes'), 'Handled.');
    await user.click(screen.getByRole('button', { name: 'Save case' }));

    expect(await screen.findByText('Check the form')).toBeInTheDocument();
    const notes = screen.getByLabelText('Handling notes');
    expect(notes).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getAllByText('This value is too long.').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('Complaint narrative')).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows an empty result with no recommendation and every bar at 0%', async () => {
    const user = userEvent.setup();
    searchMock.mockResolvedValue(searchResponse('NO_RELEVANT_CASES'));
    setup();

    await fillAndSearch(user);

    expect(await screen.findByText('No precedent cleared the threshold')).toBeInTheDocument();
    const panel = screen.getByRole('region', { name: 'Response recommendation' });
    expect(within(panel).getAllByText('0.0%')).toHaveLength(3);
    expect(within(panel).queryByText('Recommended')).not.toBeInTheDocument();
    expect((screen.getByLabelText('Company response') as HTMLSelectElement).value).toBe('');
    expect(screen.getByText('None (no precedent shown)')).toBeInTheDocument();
  });

  it('blocks save when the complaint changed after the search', async () => {
    const user = userEvent.setup();
    searchMock.mockResolvedValue(searchResponse('MATCHES', threeMatches));
    setup();

    await fillAndSearch(user);
    await findPrecedentCards();
    fireEvent.change(screen.getByLabelText('Complaint narrative'), {
      target: { value: `${NARRATIVE} Edited.` },
    });

    expect(screen.getByText('The complaint changed after this search')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save case' })).toBeDisabled();
  });

  it('keeps over-limit text intact and refuses to send it', async () => {
    const user = userEvent.setup();
    searchMock.mockResolvedValue(searchResponse('MATCHES', threeMatches));
    setup();

    await screen.findByRole('option', { name: 'Checking or savings account' });
    await user.selectOptions(screen.getByLabelText('Product'), 'Checking or savings account');
    await user.selectOptions(screen.getByLabelText('Issue'), 'Managing an account');
    const narrative = screen.getByLabelText('Complaint narrative') as HTMLTextAreaElement;
    fireEvent.change(narrative, { target: { value: '😀'.repeat(50_001) } });
    fireEvent.click(screen.getByRole('button', { name: 'Find precedents' }));

    expect(screen.getByText('The narrative must be 50,000 characters or fewer.')).toBeInTheDocument();
    expect(searchMock).not.toHaveBeenCalled();
    expect(Array.from(narrative.value)).toHaveLength(50_001);

    fireEvent.change(narrative, { target: { value: NARRATIVE } });
    await user.click(screen.getByRole('button', { name: 'Find precedents' }));
    await findPrecedentCards();

    const notes = screen.getByLabelText('Handling notes') as HTMLTextAreaElement;
    expect(notes).not.toHaveAttribute('maxlength');
    fireEvent.change(notes, { target: { value: '😀'.repeat(10_001) } });
    fireEvent.click(screen.getByRole('button', { name: 'Save case' }));

    expect(screen.getByText('Handling notes must be 10,000 characters or fewer.')).toBeInTheDocument();
    expect(retainMock).not.toHaveBeenCalled();
    expect(Array.from(notes.value)).toHaveLength(10_001);
  });

  it('marks a search failure and keeps the form', async () => {
    const user = userEvent.setup();
    searchMock.mockRejectedValue(new ApiClientError(0, 'TRANSPORT_ERROR', 'down'));
    setup();

    await fillAndSearch(user);

    expect(await screen.findByText('Cannot reach the server')).toBeInTheDocument();
    expect((screen.getByLabelText('Complaint narrative') as HTMLTextAreaElement).value).toBe(NARRATIVE);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Find precedents' })).toBeEnabled());
  });
});
