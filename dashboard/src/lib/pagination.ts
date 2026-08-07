export const DEFAULT_PAGE_SIZE_OPTIONS = [10, 20, 50, 100]

export interface PaginationWindow<T> {
  items: T[]
  currentPage: number
  totalPages: number
  start: number
  end: number
}

export function paginateItems<T>(items: T[], page: number, pageSize: number): PaginationWindow<T> {
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize))
  const currentPage = Math.min(Math.max(page, 1), totalPages)
  const startIndex = (currentPage - 1) * pageSize
  const start = items.length === 0 ? 0 : startIndex + 1
  const end = Math.min(items.length, startIndex + pageSize)

  return {
    items: items.slice(startIndex, startIndex + pageSize),
    currentPage,
    totalPages,
    start,
    end,
  }
}
