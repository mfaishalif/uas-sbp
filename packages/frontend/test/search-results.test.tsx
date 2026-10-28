import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SearchResults } from '@/components/search/SearchResults';
import { searchResponse } from './fixtures';

function renderResults(status: 'MATCHES' | 'NO_RELEVANT_CASES' | 'EMPTY_KNOWLEDGE_BASE') {
  const onToggle = vi.fn();
  const onContinue = vi.fn();
  render(
    <SearchResults
      response={searchResponse(status)}
      selectedReferenceIds={new Set()}
      onToggleReference={onToggle}
      onContinue={onContinue}
    />,
  );
  return { onToggle, onContinue };
}

describe('search result states', () => {
  it('renders MATCHES with scores, reasons, shared terms, warnings, and configuration', async () => {
    const user = userEvent.setup();
    const { onToggle, onContinue } = renderResults('MATCHES');

    expect(screen.getByText('Similarity score')).toBeInTheDocument();
    expect(screen.getByText('81.2%')).toBeInTheDocument();
    expect(screen.getByText('75.0%')).toBeInTheDocument();
    expect(screen.getByText('85.0%')).toBeInTheDocument();
    expect(screen.getByText('Category reasons')).toBeInTheDocument();
    expect(screen.getByText('Missing in case')).toBeInTheDocument();
    expect(screen.getByText('transfer')).toBeInTheDocument();
    expect(screen.getByText('payment')).toBeInTheDocument();
    expect(
      screen.getByText('Similarity calibration has not been validated by human review.'),
    ).toBeInTheDocument();
    expect(screen.getByText('tfidf-cosine-v1')).toBeInTheDocument();
    expect(screen.getByText('0.50')).toBeInTheDocument();
    expect(screen.getByText(/500 cases in the current corpus/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Use as reference' }));
    expect(onToggle).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Continue to retention' }));
    expect(onContinue).toHaveBeenCalledTimes(1);
  });

  it('renders the no-relevant-cases limitation distinctly', () => {
    renderResults('NO_RELEVANT_CASES');
    expect(screen.getByText('No relevant precedents found')).toBeInTheDocument();
    expect(screen.queryByText('Precedent matches')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Continue to retention' })).not.toBeInTheDocument();
  });

  it('renders the empty knowledge base instruction distinctly', () => {
    renderResults('EMPTY_KNOWLEDGE_BASE');
    expect(screen.getByText('The knowledge base is empty')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Import CFPB data' })).toHaveAttribute(
      'href',
      '/imports',
    );
  });
});
