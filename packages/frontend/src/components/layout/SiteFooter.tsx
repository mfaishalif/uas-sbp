import Link from 'next/link';
import { CFPB_DATABASE_URL } from '@/components/home/DataSource';
import styles from './SiteFooter.module.css';

export function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={`fin-container ${styles.inner}`}>
        <div>
          <p className={styles.brand}>FinResolve</p>
          <p className={styles.copy}>
            Precedent context for complaint analysts. Similarity is not proof that a
            response will succeed.
          </p>
        </div>
        <nav aria-label="Footer navigation" className={styles.links}>
          <Link href="/#find">Find precedents</Link>
          <Link href="/#how-it-works">How it works</Link>
          <Link href="/cases">Case library</Link>
          <a href={CFPB_DATABASE_URL} target="_blank" rel="noreferrer noopener">
            CFPB data<span className="visually-hidden"> (opens in a new tab)</span>
          </a>
        </nav>
      </div>
    </footer>
  );
}
