import { cn } from '@/lib/utils'

interface SkeletonProps {
  className?: string
}

export function Skeleton({ className }: SkeletonProps) {
  return (
    <div
      className={cn(
        'rounded-md bg-muted animate-shimmer bg-gradient-to-r from-muted via-muted/50 to-muted',
        className
      )}
    />
  )
}
