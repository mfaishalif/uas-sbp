'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { FocusFormLink } from '@/components/home/FocusFormLink';
import styles from './SiteHeader.module.css';

/** In-page sections of the landing page. Hidden on narrow screens. */
const sectionLinks = [
  { href: '/#how-it-works', label: 'How it works' },
  { href: '/#scoring', label: 'Scoring' },
  { href: '/#faq', label: 'FAQ' },
];

export function SiteHeader() {
  const pathname = usePathname();
  const libraryActive = pathname === '/cases' || pathname.startsWith('/cases/');

  return (
    <header className={styles.header}>
      <div className={`fin-container ${styles.inner}`}>
        <Link href="/" className={styles.brand} aria-label="FinResolve home">
          <span className={styles.brandMark} aria-hidden="true" />
          <span>FinResolve</span>
        </Link>
        <nav aria-label="Main navigation" className={styles.nav}>
          {sectionLinks.map((link) => (
            <Link key={link.href} href={link.href} className={`${styles.link} ${styles.section}`}>
              {link.label}
            </Link>
          ))}
          <Link
            href="/cases"
            className={libraryActive ? styles.active : styles.link}
            aria-current={libraryActive ? 'page' : undefined}
          >
            Case library
          </Link>
          <FocusFormLink className={styles.cta}>Find precedents</FocusFormLink>
        </nav>
      </div>
    </header>
  );
}
