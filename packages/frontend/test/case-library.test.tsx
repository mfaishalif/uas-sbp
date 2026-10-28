import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CaseLibraryPage } from '@/components/cases/CaseLibraryPage';
import { AppProviders } from '@/contexts/AppProviders';
import { casePage, cfpbSummary, makeClient } from './fixtures';

const { getClientMock, listCasesMock } = vi.hoisted(() => ({
  getClientMock: vi.fn(),
  listCasesMock: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  getApiClient: () => getClientMock(),
}));

vi.mock('next/link', () => ({
  default: ({ href, children, ...props }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...props}>{children}</a>
  ),
}));

describe('case library', () => {
  it('requests 20 cases with the selected source and paginates', async () => {
    const user = userEvent.setup();
    listCasesMock.mockImplementation(async ({ page, source }) => (
      casePage([cfpbSummary], page, 3, page < 2)
    ));
    getClientMock.mockReturnValue(
      makeClient({ cases: { listCases: listCasesMock, getCase: vi.fn() } }),
    );

    render(
      <AppProviders>
        <CaseLibraryPage />
      </AppProviders>,
    );

    await screen.findByText('Checking or savings account');
    expect(listCasesMock).toHaveBeenNthCalledWith(1, {
      page: 0,
      size: 20,
      source: undefined,
    });
    expect(screen.getByText(/41 cases/)).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Source'), 'CFPB');
    await waitFor(() => {
      expect(listCasesMock).toHaveBeenCalledWith({
        page: 0,
        size: 20,
        source: 'CFPB',
      });
    });

    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => {
      expect(listCasesMock).toHaveBeenCalledWith({
        page: 1,
        size: 20,
        source: 'CFPB',
      });
    });
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled();
  });
});
