import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { DEFAULT_PAGE_SIZE_OPTIONS } from '@/lib/pagination'
import { cn, formatNumber } from '@/lib/utils'
import {
  ChevronLeft,
  ChevronRight,
  ChevronsLeft,
  ChevronsRight,
} from 'lucide-react'

export function PaginationBar({
  total,
  page,
  pageSize,
  totalPages,
  start,
  end,
  totalLabel = '条记录',
  pageSizeOptions = DEFAULT_PAGE_SIZE_OPTIONS,
  className,
  onPageChange,
  onPageSizeChange,
}: {
  total: number
  page: number
  pageSize: number
  totalPages: number
  start: number
  end: number
  totalLabel?: string
  pageSizeOptions?: number[]
  className?: string
  onPageChange: (page: number) => void
  onPageSizeChange: (pageSize: number) => void
}) {
  const canGoPrevious = page > 1
  const canGoNext = page < totalPages

  return (
    <div className={cn('flex w-full flex-col gap-3 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between', className)}>
      <div className="min-w-0">
        <span>共 {formatNumber(total)} {totalLabel}</span>
        <span className="mx-2 text-border">|</span>
        <span>显示 {formatNumber(start)}-{formatNumber(end)}</span>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs">每页</span>
        <Select value={String(pageSize)} onValueChange={(value) => onPageSizeChange(Number(value))}>
          <SelectTrigger className="h-8 w-[86px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {pageSizeOptions.map((option) => (
              <SelectItem key={option} value={String(option)}>{option} 条</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <span className="min-w-[72px] text-center text-xs">第 {page} / {totalPages} 页</span>

        <div className="flex items-center gap-1">
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            onClick={() => onPageChange(1)}
            disabled={!canGoPrevious}
            aria-label="第一页"
          >
            <ChevronsLeft className="h-4 w-4" />
          </Button>
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            onClick={() => onPageChange(page - 1)}
            disabled={!canGoPrevious}
            aria-label="上一页"
          >
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            onClick={() => onPageChange(page + 1)}
            disabled={!canGoNext}
            aria-label="下一页"
          >
            <ChevronRight className="h-4 w-4" />
          </Button>
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            onClick={() => onPageChange(totalPages)}
            disabled={!canGoNext}
            aria-label="最后一页"
          >
            <ChevronsRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}
