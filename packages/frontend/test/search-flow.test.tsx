import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FindPrecedentsPage } from '@/components/search/FindPrecedentsPage';
import { AppProviders } from '@/contexts/AppProviders';
import { useAppState } from '@/contexts/AppState';
import { cfpbId, makeClient, searchResponse } from './fixtures';

const { pushMock, searchMock, getClientMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  searchMock: vi.fn(),
  getClientMock: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/',
}));

vi.mock('@/lib/api', () => ({
  getApiClient: () => getClientMock(),
}));

function DraftProbe() {
  const { retainDraft } = useAppState();
  return (
    <output data-testid="draft">
      {JSON.stringify({
        ...retainDraft,
        referenceCaseIds: Array.from(retainDraft.referenceCaseIds),
      })}
    </output>
  );
}

describe('search to retention flow', () => {
  it('carries complaint input and selected references through in-memory state', async () => {
    const user = userEvent.setup();
    searchMock.mockResolvedValue(searchResponse('MATCHES'));
    getClientMock.mockReturnValue(makeClient({ search: { searchCases: searchMock } }));
    localStorage.clear();

    render(
      <AppProviders>
        <FindPrecedentsPage />
        <DraftProbe />
      </AppProviders>,
    );

    const product = screen.getByLabelText('Product');
    await screen.findByRole('option', { name: 'Checking or savings account' });
    await user.selectOptions(product, 'Checking or savings account');
    await user.selectOptions(screen.getByLabelText('Issue'), 'Managing an account');
    await user.type(
      screen.getByLabelText('Complaint narrative'),
      'Money left my bank balance twice even though I authorized one payment.',
    );

    await user.click(screen.getByRole('button', { name: 'Find precedents' }));
    await screen.findByText('Similarity score');
    expect(searchMock).toHaveBeenCalledWith({
      searchRequest: {
        product: 'Checking or savings account',
        subProduct: null,
        issue: 'Managing an account',
        subIssue: null,
        narrative: 'Money left my bank balance twice even though I authorized one payment.',
        limit: 5,
      },
    });

    await user.click(screen.getByRole('button', { name: 'Use as reference' }));
    expect(screen.getByRole('button', { name: 'Remove reference' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await user.click(screen.getByRole('button', { name: 'Continue to retention' }));
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith('/cases/new'));

    const draft = JSON.parse(screen.getByTestId('draft').textContent ?? '{}');
    expect(draft.narrative).toBe(
      'Money left my bank balance twice even though I authorized one payment.',
    );
    expect(draft.product).toBe('Checking or savings account');
    expect(draft.issue).toBe('Managing an account');
    expect(draft.referenceCaseIds).toEqual([cfpbId]);
    expect(localStorage.length).toBe(0);
    expect(pushMock.mock.calls[0][0]).not.toContain('narrative');
  });
});
