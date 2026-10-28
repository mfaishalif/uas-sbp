import { Card } from '@/components/ui/Card';
import { LinkButton } from '@/components/ui/LinkButton';
import styles from './StatusPage.module.css';

export default function NotFound() {
  return (
    <div className={`fin-container ${styles.page}`}>
      <Card className={styles.card}>
        <p className="page-eyebrow">404</p>
        <h1>Page not found</h1>
        <p>
          This page does not exist. Return to precedent search, or browse the case
          library for an available record.
        </p>
        <div className={styles.actions}>
          <LinkButton href="/">Find precedents</LinkButton>
          <LinkButton href="/cases" variant="tertiary">Case library</LinkButton>
        </div>
      </Card>
    </div>
  );
}
