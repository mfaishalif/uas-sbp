'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import styles from './SiteHeader.module.css';

const links = [
  { href: '/', label: 'Find precedents' },
  { href: '/cases', label: 'Case library' },
  { href: '/imports', label: 'Import data' },
];

export function SiteHeader() {
  const pathname = usePathname();
  return (
    <header className={styles.header}>
      <div className={`fin-container ${styles.inner}`}>
        <Link href="/" className={styles.brand} aria-label="FinResolve home">
          <span className={styles.brandMark} aria-hidden="true" />
          <span>FinResolve</span>
        </Link>
        <nav aria-label="Main navigation" className={styles.nav}>
          {links.map((link) => {
            const active =
              link.href === '/'
                ? pathname === '/'
                : pathname === link.href || pathname.startsWith(`${link.href}/`);
            return (
              <Link
                key={link.href}
                href={link.href}
                className={active ? styles.active : styles.link}
                aria-current={active ? 'page' : undefined}
              >
                {link.label}
              </Link>
            );
          })}
        </nav>
      </div>
    </header>
  );
}
