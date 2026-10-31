import * as React from 'react'
import { cn } from '@/lib/utils'

function Label({ className, ...props }: React.ComponentProps<'label'>) {
  return (
    <label
      data-slot="label"
      className={cn(
        'mb-1.5 block text-sm font-medium text-foreground',
        className,
      )}
      {...props}
    />
  )
}

function FieldError({ children }: { children?: string }) {
  return (
    <p className="mt-1 min-h-4 text-xs leading-4 text-destructive">
      {children ?? ''}
    </p>
  )
}

export { Label, FieldError }
