import { LinkButton } from '@/components/ui/LinkButton';
import { FocusFormLink } from './FocusFormLink';
import styles from './Sections.module.css';

/** hero-band-dark: lime headline on ink. */
export function ClosingCta() {
  return (
    <section className={styles.darkBand} aria-labelledby="closing-title">
      <div className={`fin-container ${styles.closing}`}>
        <h2 className={styles.closingTitle} id="closing-title">
          Got a new complaint? Start from what worked before.
        </h2>
        <p className={styles.closingLead}>
          Three precedents, one weighted recommendation, and a case saved for the next
          analyst.
        </p>
        <div className={styles.closingActions}>
          <FocusFormLink>Find precedents</FocusFormLink>
          <LinkButton href="/cases" variant="tertiary">Browse case library</LinkButton>
        </div>
      </div>
    </section>
  );
}
