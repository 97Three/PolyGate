import { cn } from '@/lib/utils'

interface SparklineProps {
  data: number[]
  width?: number
  height?: number
  color?: string
  className?: string
}

export function Sparkline({ data, width = 80, height = 24, color, className }: SparklineProps) {
  if (!data.length) return null

  const min = Math.min(...data)
  const max = Math.max(...data)
  const range = max - min || 1
  const padding = 2

  const points = data.map((v, i) => {
    const x = padding + (i / (data.length - 1)) * (width - padding * 2)
    const y = height - padding - ((v - min) / range) * (height - padding * 2)
    return `${x},${y}`
  })

  const pathD = `M${points.join(' L')}`
  const areaD = `${pathD} L${width - padding},${height} L${padding},${height} Z`

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={cn('overflow-visible', className)}
    >
      <defs>
        <linearGradient id={`sparkline-grad-${height}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color || 'var(--primary)'} stopOpacity={0.3} />
          <stop offset="100%" stopColor={color || 'var(--primary)'} stopOpacity={0} />
        </linearGradient>
      </defs>
      <path d={areaD} fill={`url(#sparkline-grad-${height})`} />
      <path
        d={pathD}
        fill="none"
        stroke={color || 'var(--primary)'}
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
