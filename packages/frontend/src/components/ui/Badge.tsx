import type { ReactNode } from 'react';
import { cx } from '@/lib/classNames';
import styles from './Badge.module.css';

export type BadgeTone =
  | 'neutral'
  | 'ink'
  | 'positive'
  | 'warning'
  | 'negative'
  | 'missing';

export function Badge({
  children,
  tone = 'neutral',
  className,
}: {
  children: ReactNode;
  tone?: BadgeTone;
  className?: string;
}) {
  return (
    <span className={cx(styles.badge, styles[tone], className)}>{children}</span>
  );
}
