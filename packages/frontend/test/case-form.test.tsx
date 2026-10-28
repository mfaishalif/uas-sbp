import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { CaseDetail } from '@finresolve/api-client';
import { CaseForm } from '@/components/cases/CaseForm';
import { categoriesFixture, analystCase } from './fixtures';
import { emptyCaseForm } from '@/lib/case';

const refreshMock = vi.fn();
const cacheReferenceMock = vi.fn();
const notifyMock = vi.fn();

vi.mock('@/contexts/CatalogContext', () => ({
  useCatalog: () => ({
    items: categoriesFixture,
    loading: false,
    error: null,
    refresh: refreshMock,
  }),
}));

vi.mock('@/contexts/AppState', () => ({
  useAppState: () => ({
    referenceCache: {},
    cacheReference: cacheReferenceMock,
    notifyDataChanged: notifyMock,
  }),
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('case form submission safety', () => {
  it('prevents a second submission while saving and disables the save button', async () => {
    const pending = deferred<CaseDetail>();
    const onSubmit = vi.fn(() => pending.promise);
    const onSuccess = vi.fn();
    const onChange = vi.fn();
    const values = {
      ...emptyCaseForm(),
      product: 'Checking or savings account',
      subProduct: 'Checking account',
      issue: 'Managing an account',
      narrative: 'Money left my account twice.',
      handlingNotes: 'Confirmed the duplicate transfer and requested a reversal.',
    };

    const { container } = render(
      <CaseForm
        mode="create"
        values={values}
        onChange={onChange}
        onSubmit={onSubmit}
        onSuccess={onSuccess}
        cancelHref="/cases"
      />,
    );

    fireEvent.submit(container.querySelector('form')!);
    fireEvent.submit(container.querySelector('form')!);

    expect(onSubmit).toHaveBeenCalledTimes(1);
    const saveButton = screen.getByRole('button', { name: 'Saving…' });
    expect(saveButton).toBeDisabled();

    pending.resolve(analystCase);
    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(analystCase));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});
