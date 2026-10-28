'use client';

import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { LinkButton } from '@/components/ui/LinkButton';
import styles from './StatusPage.module.css';

export default function ErrorPage({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className={`fin-container ${styles.page}`}>
      <Card className={styles.card}>
        <p className="page-eyebrow">Unexpected error</p>
        <h1>Something interrupted this page</h1>
        <p>
          The page could not be displayed. No case data or form values are discarded by
          this message; try loading the view again.
        </p>
        <div className={styles.actions}>
          <Button onClick={() => reset()}>Try again</Button>
          <LinkButton href="/" variant="tertiary">Find precedents</LinkButton>
        </div>
      </Card>
    </div>
  );
}
