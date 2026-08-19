import { describe, expect, it } from 'vitest'

import { serviceAccountExpiryError } from './service-account-expiry'

describe('service account expiry validation', () => {
  const now = new Date('2026-08-09T00:00:00.000Z').getTime()

  it('requires a valid future expiry within 90 days', () => {
    expect(serviceAccountExpiryError('', now)).toContain('必须设置')
    expect(serviceAccountExpiryError('not-a-date', now)).toContain('有效')
    expect(serviceAccountExpiryError('2026-08-08T00:00:00.000Z', now)).toContain('晚于')
    expect(serviceAccountExpiryError('2026-11-08T00:00:00.000Z', now)).toContain('90 天')
    expect(serviceAccountExpiryError('2026-11-07T00:00:00.000Z', now)).toBeNull()
  })
})
