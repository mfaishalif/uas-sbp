'use client';

import type { MouseEvent, ReactNode } from 'react';
import { cx } from '@/lib/classNames';
import styles from '@/components/ui/Button.module.css';

export const SEARCH_FORM_ANCHOR = 'find';

const FOCUS_ORDER = ['search-product', 'search-narrative'];

/**
 * Moves to the search widget. On the landing page it scrolls there and focuses
 * the first usable field; elsewhere (or without JavaScript) it is a plain link
 * to /#find.
 */
export function FocusFormLink({
  children,
  variant = 'primary',
  className,
}: {
  children: ReactNode;
  variant?: 'primary' | 'secondary' | 'tertiary' | 'quiet' | 'none';
  className?: string;
}) {
  function handleClick(event: MouseEvent<HTMLAnchorElement>) {
    const target = FOCUS_ORDER
      .map((id) => document.getElementById(id))
      .find(
        (element): element is HTMLElement =>
          element instanceof HTMLElement && !element.hasAttribute('disabled'),
      );
    const anchor = document.getElementById(SEARCH_FORM_ANCHOR);
    if (!target || !anchor) return;

    event.preventDefault();
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    anchor.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
    target.focus({ preventScroll: true });
  }

  return (
    <a
      href={`/#${SEARCH_FORM_ANCHOR}`}
      onClick={handleClick}
      className={variant === 'none' ? className : cx(styles.button, styles[variant], className)}
    >
      {variant === 'none' ? children : <span>{children}</span>}
    </a>
  );
}
