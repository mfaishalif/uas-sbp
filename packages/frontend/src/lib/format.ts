export function formatScore(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

export function formatWeight(value: number): string {
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 3,
    useGrouping: false,
  }).format(value);
}

export function formatDateTime(value: string | null): string {
  if (!value) return 'Not recorded';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('en-US', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'UTC',
  }).format(date);
}

export function formatDate(value: string | null): string {
  if (!value) return 'Not recorded';
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const date = new Date(`${value}T00:00:00Z`);
    if (!Number.isNaN(date.getTime())) {
      return new Intl.DateTimeFormat('en-US', {
        dateStyle: 'medium',
        timeZone: 'UTC',
      }).format(date);
    }
  }
  return value;
}

export function truncate(value: string, length = 260): string {
  const characters = Array.from(value);
  if (characters.length <= length) return value;
  return `${characters.slice(0, length).join('')}…`;
}

export function textLength(value: string): number {
  return Array.from(value).length;
}

export function outcomeLabel(value: string): string {
  switch (value) {
    case 'RESOLVED':
      return 'Resolved';
    case 'PARTIALLY_RESOLVED':
      return 'Partially resolved';
    case 'UNRESOLVED':
      return 'Unresolved';
    default:
      return 'Not assessed';
  }
}

export function sourceLabel(value: string): string {
  // Source values are contract data and stay exactly as the API returns them.
  return value;
}

export function categoryReasonLabel(status: string): string {
  switch (status) {
    case 'MATCH':
      return 'Match';
    case 'MISMATCH':
      return 'Mismatch';
    case 'MISSING_IN_CASE':
      return 'Missing in case';
    default:
      return 'Not used';
  }
}

export function fieldLabel(value: string): string {
  switch (value) {
    case 'product':
      return 'Product';
    case 'subProduct':
      return 'Sub-product';
    case 'issue':
      return 'Issue';
    case 'subIssue':
      return 'Sub-issue';
    default:
      return value;
  }
}
