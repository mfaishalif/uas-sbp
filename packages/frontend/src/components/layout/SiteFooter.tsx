import Link from 'next/link';
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
          <Link href="/">Find precedents</Link>
          <Link href="/cases">Case library</Link>
          <Link href="/imports">Import data</Link>
        </nav>
      </div>
    </footer>
  );
}
