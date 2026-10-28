import styles from './LoadingState.module.css';

export function LoadingState({
  label = 'Loading…',
  compact = false,
}: {
  label?: string;
  compact?: boolean;
}) {
  return (
    <div className={compact ? styles.compact : styles.loading} role="status" aria-live="polite">
      <span className={styles.spinner} aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}
