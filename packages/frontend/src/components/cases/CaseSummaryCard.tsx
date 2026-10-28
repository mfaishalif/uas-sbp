'use client';

import Link from 'next/link';
import type { CaseSummary } from '@finresolve/api-client';
import { Badge } from '@/components/ui/Badge';
import { Card } from '@/components/ui/Card';
import { categoryPath } from '@/lib/case';
import { formatDate, sourceLabel } from '@/lib/format';
import styles from './CaseSummaryCard.module.css';

export function CaseSummaryCard({ item }: { item: CaseSummary }) {
  return (
    <Card as="article" className={styles.card}>
      <div className={styles.head}>
        <div className={styles.badges}>
          <Badge tone={item.source === 'CFPB' ? 'neutral' : 'ink'}>
            {sourceLabel(item.source)}
          </Badge>
          {item.company ? <span className={styles.company}>{item.company}</span> : null}
        </div>
        <span className={styles.date}>{formatDate(item.receivedDate)}</span>
      </div>
      <h2 className={styles.title}>
        <Link href={`/cases/${item.id}`}>{item.product}</Link>
        <span className={styles.issue}> · {item.issue}</span>
      </h2>
      {item.subProduct || item.subIssue ? (
        <p className={styles.path}>
          {[item.subProduct, item.subIssue].filter(Boolean).join(' / ')}
        </p>
      ) : null}
      <div className={styles.footer}>
        <span className={styles.meta}>
          Updated {formatDate(item.updatedAt.slice(0, 10))}
        </span>
        <Link className={styles.open} href={`/cases/${item.id}`}>
          View case
          <span aria-hidden="true"> →</span>
        </Link>
      </div>
      <span className="visually-hidden">
        Category: {categoryPath({
          product: item.product,
          subProduct: item.subProduct,
          issue: item.issue,
          subIssue: item.subIssue,
        })}
      </span>
    </Card>
  );
}
