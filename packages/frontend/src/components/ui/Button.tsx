'use client';

import type { ButtonHTMLAttributes } from 'react';
import { cx } from '@/lib/classNames';
import styles from './Button.module.css';

export type ButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'quiet';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  busy?: boolean;
  fullWidth?: boolean;
}

export function Button({
  variant = 'primary',
  busy = false,
  fullWidth = false,
  className,
  children,
  disabled,
  type = 'button',
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      type={type}
      className={cx(
        styles.button,
        styles[variant],
        fullWidth && styles.fullWidth,
        className,
      )}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
    >
      {busy ? <span className={styles.spinner} aria-hidden="true" /> : null}
      <span>{children}</span>
    </button>
  );
}
