import { Skeleton } from './Skeleton'

export function LoadingPage() {
  return (
    <div className="space-y-6 p-1">
      {/* Header skeleton */}
      <div className="flex items-center justify-between">
        <div className="space-y-2">
          <Skeleton className="h-7 w-48" />
          <Skeleton className="h-4 w-72" />
        </div>
        <Skeleton className="h-9 w-28" />
      </div>

      {/* Metric cards skeleton */}
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="rounded-xl border p-6 space-y-3">
            <div className="flex items-center justify-between">
              <Skeleton className="h-4 w-24" />
              <Skeleton className="h-8 w-8 rounded-lg" />
            </div>
            <Skeleton className="h-8 w-32" />
            <Skeleton className="h-3 w-40" />
          </div>
        ))}
      </div>

      {/* Chart skeleton */}
      <div className="grid gap-4 lg:grid-cols-7">
        <div className="lg:col-span-4 rounded-xl border p-6 space-y-4">
          <Skeleton className="h-5 w-36" />
          <Skeleton className="h-[250px] w-full" />
        </div>
        <div className="lg:col-span-3 rounded-xl border p-6 space-y-4">
          <Skeleton className="h-5 w-36" />
          <Skeleton className="h-[250px] w-full" />
        </div>
      </div>

      {/* Table skeleton */}
      <div className="rounded-xl border p-6 space-y-4">
        <Skeleton className="h-5 w-24" />
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-12 w-full" />
          ))}
        </div>
      </div>
    </div>
  )
}
