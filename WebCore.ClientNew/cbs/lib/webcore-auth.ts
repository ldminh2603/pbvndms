const apiPath = '/api/webcore/auth'

export const authSessionStorageKey = 'has_auth_session'
export const activeUserEmailStorageKey = 'active_user_email'
export const rememberedEmailStorageKey = 'remembered_email'
const selectedComponentStorageKey = 'selected_component'
export const webCoreAuthenticationExpiredEvent = 'webcore:authentication-expired'
let authenticationExpirySignaled = false

export function sessionWasCleared(response: Response) {
  return response.headers.get('x-webcore-session-cleared') === 'true'
}

export function clearClientAuthenticationState() {
  window.localStorage.removeItem(authSessionStorageKey)
  window.localStorage.removeItem(selectedComponentStorageKey)
  window.sessionStorage.removeItem(activeUserEmailStorageKey)
}

export function expireClientAuthentication() {
  if (authenticationExpirySignaled) return
  authenticationExpirySignaled = true
  clearClientAuthenticationState()
  window.dispatchEvent(new Event(webCoreAuthenticationExpiredEvent))
}

export async function signIn(email: string, password: string, rememberMe: boolean) {
  const response = await fetch(`${apiPath}/login`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, rememberMe }),
  })
  if (response.ok) authenticationExpirySignaled = false
  return response
}

export async function signOut() {
  return fetch(`${apiPath}/logout`, {
    method: 'POST',
    credentials: 'include',
  })
}

async function requestCurrentUser() {
  return fetch(`${apiPath}/me`, { credentials: 'include' })
}

export async function refreshAccessToken() {
  return fetch(`${apiPath}/refresh`, {
    method: 'POST',
    credentials: 'include',
  })
}

export async function getCurrentUser() {
  const userResponse = await requestCurrentUser()
  // The Next auth proxy converts an upstream 401 from /me to 204 so an
  // expected expired-cookie check does not pollute the browser console. Treat
  // both status codes as an expired access token and use the refresh cookie.
  if (userResponse.status !== 401 && userResponse.status !== 204) return userResponse

  const refreshResponse = await refreshAccessToken()
  if (!refreshResponse.ok) return userResponse

  return requestCurrentUser()
}

export async function getErrorMessage(response: Response) {
  const payload = await response.json().catch(() => null) as {
    errorMessage?: unknown
    message?: unknown
    error?: unknown
    detail?: unknown
  } | null

  return typeof payload?.errorMessage === 'string'
    ? payload.errorMessage
    : typeof payload?.message === 'string'
      ? payload.message
      : typeof payload?.error === 'string'
        ? payload.error
        : typeof payload?.detail === 'string'
          ? payload.detail
          : 'Authentication failed.'
}
