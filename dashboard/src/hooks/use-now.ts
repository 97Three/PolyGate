import { useEffect, useState } from 'react'

/** Keep expiry-derived UI state from freezing at page mount time. */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs])

  return now
}
