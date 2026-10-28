import type { ReactNode } from 'react';
import { cx } from '@/lib/classNames';
import styles from './StatusMessage.module.css';

export type StatusTone = 'info' | 'success' | 'warning' | 'error';

export function StatusMessage({
  tone = 'info',
  title,
  children,
  className,
  role,
}: {
  tone?: StatusTone;
  title?: string;
  children: ReactNode;
  className?: string;
  role?: 'alert' | 'status';
}) {
  const defaultRole = tone === 'error' ? 'alert' : 'status';
  return (
    <div
      className={cx(styles.message, styles[tone], className)}
      role={role ?? defaultRole}
    >
      {title ? <p className={styles.title}>{title}</p> : null}
      <div className={styles.body}>{children}</div>
    </div>
  );
}
