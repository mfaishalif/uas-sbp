import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiClientError } from '@finresolve/api-client';
import { ImportPage } from '@/components/imports/ImportPage';
import { importReport } from './fixtures';
import { MAX_FILE_BYTES } from '@/lib/imports';

const { importMock, refreshMock, notifyMock, getClientMock } = vi.hoisted(() => ({
  importMock: vi.fn(),
  refreshMock: vi.fn(),
  notifyMock: vi.fn(),
  getClientMock: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  getApiClient: () => getClientMock(),
}));

vi.mock('@/contexts/CatalogContext', () => ({
  useCatalog: () => ({
    items: [],
    loading: false,
    error: null,
    refresh: refreshMock,
  }),
}));

vi.mock('@/contexts/AppState', () => ({
  useAppState: () => ({
    notifyDataChanged: notifyMock,
    referenceCache: {},
    cacheReference: vi.fn(),
  }),
}));

async function fillForm(file: File) {
  const user = userEvent.setup();
  await user.upload(screen.getByLabelText('CSV file'), file);
  await user.type(
    screen.getByLabelText('Archive URL'),
    'https://example.gov/archive.zip',
  );
  await user.clear(screen.getByLabelText('Source file name'));
  await user.type(
    screen.getByLabelText('Source file name'),
    'sample.csv',
  );
  await user.type(screen.getByLabelText('Period start'), '2024-11-01');
  await user.type(screen.getByLabelText('Period end'), '2024-12-31');
  return user;
}

describe('CSV import flow', () => {
  it('shows counts, row examples, rejected rows, and refreshes catalog after success', async () => {
    importMock.mockResolvedValue(importReport);
    getClientMock.mockReturnValue({
      imports: { importCfpb: importMock },
      categories: { getCategories: vi.fn() },
    });
    render(<ImportPage />);

    const file = new File(['Complaint ID,Product\n'], 'sample.csv', { type: 'text/csv' });
    const user = await fillForm(file);
    await user.click(screen.getByRole('button', { name: 'Import CSV' }));

    expect(await screen.findByText('Import completed')).toBeInTheDocument();
    expect(importMock).toHaveBeenCalledTimes(1);
    const call = importMock.mock.calls[0][0];
    expect(call.file).toBe(file);
    expect(call.metadata).toEqual({
      archiveUrl: 'https://example.gov/archive.zip',
      sourceFileName: 'sample.csv',
      periodStart: '2024-11-01',
      periodEnd: '2024-12-31',
    });

    for (const label of ['Processed', 'Imported', 'Duplicates', 'Rejected']) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('1 record rejected')).toBeInTheDocument();
    expect(screen.getByText('INVALID_FORMAT')).toBeInTheDocument();
    expect(
      screen.getByText('This value has an invalid format.'),
    ).toBeInTheDocument();
    expect(refreshMock).toHaveBeenCalledTimes(1);
    expect(notifyMock).toHaveBeenCalledTimes(1);
  });

  it('distinguishes a replay and preserves the original counts', async () => {
    importMock.mockResolvedValue({ ...importReport, replayed: true });
    getClientMock.mockReturnValue({
      imports: { importCfpb: importMock },
      categories: { getCategories: vi.fn() },
    });
    render(<ImportPage />);

    const user = await fillForm(new File(['csv'], 'sample.csv', { type: 'text/csv' }));
    await user.click(screen.getByRole('button', { name: 'Import CSV' }));

    expect(await screen.findByText('Replay detected')).toBeInTheDocument();
    expect(screen.getByText('Existing batch returned')).toBeInTheDocument();
    expect(screen.getByText(/no new rows were added/i)).toBeInTheDocument();
    expect(within(screen.getByText('Imported').parentElement!).getByText('1')).toBeInTheDocument();
  });

  it('rejects a file larger than 20 MiB before calling the API', async () => {
    importMock.mockResolvedValue(importReport);
    getClientMock.mockReturnValue({
      imports: { importCfpb: importMock },
      categories: { getCategories: vi.fn() },
    });
    render(<ImportPage />);

    const tooLarge = new File(['x'], 'huge.csv', { type: 'text/csv' });
    Object.defineProperty(tooLarge, 'size', { value: MAX_FILE_BYTES + 1 });
    const user = await fillForm(tooLarge);
    await user.click(screen.getByRole('button', { name: 'Import CSV' }));

    expect(
      screen.getByText('The CSV must be 20 MiB (20,971,520 bytes) or smaller.'),
    ).toBeInTheDocument();
    expect(importMock).not.toHaveBeenCalled();
  });

  it('keeps file and metadata after a backend transport failure', async () => {
    importMock.mockRejectedValue(
      new ApiClientError(0, 'TRANSPORT_ERROR', 'Tidak dapat menghubungi server.'),
    );
    getClientMock.mockReturnValue({
      imports: { importCfpb: importMock },
      categories: { getCategories: vi.fn() },
    });
    render(<ImportPage />);

    const file = new File(['csv'], 'keep-me.csv', { type: 'text/csv' });
    const user = await fillForm(file);
    await user.click(screen.getByRole('button', { name: 'Import CSV' }));

    expect(await screen.findByText('Cannot reach the server')).toBeInTheDocument();
    expect(screen.getByLabelText('Archive URL')).toHaveValue(
      'https://example.gov/archive.zip',
    );
    expect(screen.getByLabelText('Source file name')).toHaveValue('sample.csv');
    expect(screen.getByLabelText('Period start')).toHaveValue('2024-11-01');
    expect(screen.getByLabelText('Period end')).toHaveValue('2024-12-31');
    expect(screen.getByText(/keep-me\.csv/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Import CSV' })).toBeEnabled();
    expect(screen.getByText('Import CSV')).toBeInTheDocument();
  });

  it('requires HTTPS metadata', async () => {
    importMock.mockResolvedValue(importReport);
    getClientMock.mockReturnValue({
      imports: { importCfpb: importMock },
      categories: { getCategories: vi.fn() },
    });
    render(<ImportPage />);

    const user = userEvent.setup();
    await user.upload(
      screen.getByLabelText('CSV file'),
      new File(['csv'], 'sample.csv', { type: 'text/csv' }),
    );
    await user.type(screen.getByLabelText('Archive URL'), 'http://example.gov/archive.zip');
    await user.type(screen.getByLabelText('Source file name'), 'sample.csv');
    await user.type(screen.getByLabelText('Period start'), '2024-11-01');
    await user.type(screen.getByLabelText('Period end'), '2024-12-31');
    await user.click(screen.getByRole('button', { name: 'Import CSV' }));

    expect(screen.getByText('Enter a valid HTTPS archive URL.')).toBeInTheDocument();
    expect(importMock).not.toHaveBeenCalled();
  });
});
