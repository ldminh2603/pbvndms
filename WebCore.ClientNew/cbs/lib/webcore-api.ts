import { expireClientAuthentication } from '@/lib/webcore-auth'

const apiBasePath = '/api/webcore-api'

export async function webCoreApiResponse(path: string, init?: RequestInit): Promise<Response> {
  const selectedComponent = typeof window === 'undefined'
    ? undefined
    : window.localStorage.getItem('selected_component')?.trim() || undefined

  const request = () => fetch(`${apiBasePath}${path}`, {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(selectedComponent ? { selected_component: selectedComponent } : {}),
      ...(init?.headers ?? {}),
    },
  })
  let response = await request()

  // Access tokens are intentionally short-lived. A page may stay open until a
  // privileged POST is clicked, so retry the original request once after the
  // HttpOnly refresh-cookie flow has issued a new access token.
  if (response.status === 401) {
    const refreshed = await fetch('/api/webcore/auth/refresh', {
      method: 'POST',
      credentials: 'include',
    })
    if (refreshed.ok) response = await request()
  }

  // A persistent 401 means both the access token and refresh session are no
  // longer usable. Notify the app shell once so mounted pages unmount and stop
  // their polling timers instead of repeatedly logging 401 responses.
  if (response.status === 401 && typeof window !== 'undefined') {
    expireClientAuthentication()
  }

  return response
}

export async function webCoreApi<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await webCoreApiResponse(path, init)

  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { errorMessage?: string; message?: string; detail?: string; title?: string } | null
    throw new Error(payload?.errorMessage ?? payload?.message ?? payload?.detail ?? payload?.title ?? `Request failed (${response.status}).`)
  }

  return response.json() as Promise<T>
}
