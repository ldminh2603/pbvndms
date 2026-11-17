/** Generates a browser-safe unique ID when older Web Crypto implementations lack randomUUID(). */
export function createClientId(): string {
  const cryptoApi = typeof globalThis === 'undefined'
    ? undefined
    : globalThis.crypto as Crypto & { randomUUID?: () => string }

  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID()
  if (typeof cryptoApi?.getRandomValues === 'function') {
    const values = cryptoApi.getRandomValues(new Uint32Array(4))
    return Array.from(values, value => value.toString(16).padStart(8, '0')).join('-')
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}
