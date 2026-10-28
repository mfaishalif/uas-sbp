import type { HTMLAttributes, ReactNode } from 'react';
import { cx } from '@/lib/classNames';
import styles from './Card.module.css';

interface CardProps extends HTMLAttributes<HTMLElement> {
  children: ReactNode;
  as?: 'div' | 'section' | 'article' | 'aside';
  tone?: 'white' | 'sage' | 'green' | 'dark';
}

export function Card({
  children,
  as: Tag = 'div',
  tone = 'white',
  className,
  ...props
}: CardProps) {
  return (
    <Tag
      {...props}
      className={cx(styles.card, styles[tone], className)}
    >
      {children}
    </Tag>
  );
}
