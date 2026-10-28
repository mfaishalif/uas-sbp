'use client';

import {
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { cx } from '@/lib/classNames';
import styles from './FormField.module.css';

function describedBy(id: string, hint?: string, error?: string): string | undefined {
  const ids = [hint ? `${id}-hint` : null, error ? `${id}-error` : null].filter(Boolean);
  return ids.length ? ids.join(' ') : undefined;
}

function FieldShell({
  id,
  label,
  hint,
  error,
  optional,
  className,
  children,
}: {
  id: string;
  label: string;
  hint?: string;
  error?: string;
  optional?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={cx(styles.field, className)}>
      <label className={styles.label} htmlFor={id}>
        {label}
        {optional ? <span className={styles.optional}> (optional)</span> : null}
      </label>
      {children}
      {hint ? (
        <p className={styles.hint} id={`${id}-hint`}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p className={styles.error} id={`${id}-error`}>
          {error}
        </p>
      ) : null}
    </div>
  );
}

interface CommonProps {
  label: string;
  hint?: string;
  error?: string;
  optional?: boolean;
  containerClassName?: string;
}

export interface TextInputProps
  extends CommonProps,
    Omit<InputHTMLAttributes<HTMLInputElement>, 'className'> {
  id?: string;
}

export function TextInput({
  label,
  hint,
  error,
  optional,
  containerClassName,
  id,
  ...props
}: TextInputProps) {
  const generatedId = useId();
  const fieldId = id ?? `field-${generatedId}`;
  return (
    <FieldShell
      id={fieldId}
      label={label}
      hint={hint}
      error={error}
      optional={optional}
      className={containerClassName}
    >
      <input
        {...props}
        id={fieldId}
        className={cx(styles.control, error && styles.invalid)}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(fieldId, hint, error)}
      />
    </FieldShell>
  );
}

export interface TextareaProps
  extends CommonProps,
    Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'className'> {
  id?: string;
}

export function Textarea({
  label,
  hint,
  error,
  optional,
  containerClassName,
  id,
  ...props
}: TextareaProps) {
  const generatedId = useId();
  const fieldId = id ?? `field-${generatedId}`;
  return (
    <FieldShell
      id={fieldId}
      label={label}
      hint={hint}
      error={error}
      optional={optional}
      className={containerClassName}
    >
      <textarea
        {...props}
        id={fieldId}
        className={cx(styles.control, styles.textarea, error && styles.invalid)}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(fieldId, hint, error)}
      />
    </FieldShell>
  );
}

export interface SelectProps
  extends CommonProps,
    Omit<SelectHTMLAttributes<HTMLSelectElement>, 'className'> {
  id?: string;
  children: ReactNode;
}

export function Select({
  label,
  hint,
  error,
  optional,
  containerClassName,
  id,
  children,
  ...props
}: SelectProps) {
  const generatedId = useId();
  const fieldId = id ?? `field-${generatedId}`;
  return (
    <FieldShell
      id={fieldId}
      label={label}
      hint={hint}
      error={error}
      optional={optional}
      className={containerClassName}
    >
      <select
        {...props}
        id={fieldId}
        className={cx(styles.control, styles.select, error && styles.invalid)}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(fieldId, hint, error)}
      >
        {children}
      </select>
    </FieldShell>
  );
}

export function FieldGroup({
  legend,
  children,
  className,
}: {
  legend: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <fieldset className={cx(styles.group, className)}>
      <legend className={styles.legend}>{legend}</legend>
      {children}
    </fieldset>
  );
}
