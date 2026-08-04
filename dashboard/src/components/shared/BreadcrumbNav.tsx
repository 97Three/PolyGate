import { useLocation, Link } from 'react-router-dom'
import { ChevronRight, Home } from 'lucide-react'
import { NAV_ITEMS } from '@/lib/constants'

export function BreadcrumbNav() {
  const location = useLocation()
  const segments = location.pathname.split('/').filter(Boolean)

  if (segments.length === 0) return null

  const items = segments.map((segment, index) => {
    const path = '/' + segments.slice(0, index + 1).join('/')
    const navItem = NAV_ITEMS.find((item) => item.path === path)
    return {
      label: navItem?.label || segment,
      path,
      isLast: index === segments.length - 1,
    }
  })

  return (
    <nav className="flex items-center gap-1 text-sm text-muted-foreground">
      <Link to="/dashboard" className="flex items-center gap-1 hover:text-foreground transition-colors">
        <Home className="h-3.5 w-3.5" />
      </Link>
      {items.map((item) => (
        <span key={item.path} className="flex items-center gap-1">
          <ChevronRight className="h-3.5 w-3.5" />
          {item.isLast ? (
            <span className="font-medium text-foreground">{item.label}</span>
          ) : (
            <Link to={item.path} className="hover:text-foreground transition-colors">
              {item.label}
            </Link>
          )}
        </span>
      ))}
    </nav>
  )
}
