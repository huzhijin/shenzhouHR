import { Button, type ButtonProps } from 'antd';
import type { ComponentPropsWithoutRef, ReactNode } from 'react';

type AccessibleButtonProps = Omit<ButtonProps, 'aria-label'> & {
  label: string;
  children?: ReactNode;
  iconOnly?: boolean;
};

export function AccessibleButton({
  label,
  children,
  iconOnly = false,
  ...props
}: AccessibleButtonProps) {
  return (
    <Button {...props} aria-label={label}>
      {children ?? (iconOnly ? null : label)}
    </Button>
  );
}

type AccessibleNativeButtonProps = Omit<
  ComponentPropsWithoutRef<'button'>,
  'aria-label'
> & {
  label: string;
};

export function AccessibleNativeButton({
  label,
  children,
  ...props
}: AccessibleNativeButtonProps) {
  return <button {...props} aria-label={label}>{children ?? label}</button>;
}
