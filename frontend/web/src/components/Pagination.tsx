import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'

type PaginationProps = {
  page: number
  size: number
  total: number
  onChange: (page: number) => void
}

function buildPages(current: number, totalPages: number) {
  const pages = new Set<number>()
  pages.add(1)
  pages.add(totalPages)
  for (let i = current - 1; i <= current + 1; i += 1) {
    if (i >= 1 && i <= totalPages) pages.add(i)
  }
  return [...pages].sort((a, b) => a - b)
}

export function Pagination({ page, size, total, onChange }: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(total / Math.max(1, size)))
  const pages = buildPages(page, totalPages)

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 pt-2">
      <span className="text-sm text-muted-foreground">
        共 {total} 条，第 {page} / {totalPages} 页
      </span>
      <div className="flex items-center gap-1">
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          aria-label="上一页"
          disabled={page <= 1}
          onClick={() => onChange(page - 1)}
        >
          <ChevronLeft />
        </Button>
        {pages.map((item, index) => {
          const previous = pages[index - 1]
          const showEllipsis = previous !== undefined && item - previous > 1
          return (
            <span key={item} className="flex items-center gap-1">
              {showEllipsis && (
                <span className="px-1 text-sm text-muted-foreground">…</span>
              )}
              <Button
                type="button"
                variant={item === page ? 'default' : 'outline'}
                size="icon-sm"
                onClick={() => onChange(item)}
              >
                {item}
              </Button>
            </span>
          )
        })}
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          aria-label="下一页"
          disabled={page >= totalPages}
          onClick={() => onChange(page + 1)}
        >
          <ChevronRight />
        </Button>
      </div>
    </div>
  )
}
