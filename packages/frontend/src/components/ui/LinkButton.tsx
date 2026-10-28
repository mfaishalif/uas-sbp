import Link from 'next/link';
import type { AnchorHTMLAttributes, ReactNode } from 'react';
import { cx } from '@/lib/classNames';
import styles from './Button.module.css';

export interface LinkButtonProps extends AnchorHTMLAttributes<HTMLAnchorElement> {
  href: string;
  variant?: 'primary' | 'secondary' | 'tertiary' | 'quiet';
  children: ReactNode;
  fullWidth?: boolean;
}

export function LinkButton({
  href,
  variant = 'primary',
  className,
  children,
  fullWidth = false,
  ...props
}: LinkButtonProps) {
  return (
    <Link
      href={href}
      className={cx(
        styles.button,
        styles[variant],
        fullWidth && styles.fullWidth,
        className,
      )}
      {...props}
    >
      <span>{children}</span>
    </Link>
  );
}
