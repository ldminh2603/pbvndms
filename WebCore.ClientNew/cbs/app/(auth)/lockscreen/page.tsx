'use client'

import { withBase } from '@/lib/base'
import { useEffect, useState, type FormEvent } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import {
  activeUserEmailStorageKey,
  authSessionStorageKey,
  clearClientAuthenticationState,
  getCurrentUser,
  getErrorMessage,
  rememberedEmailStorageKey,
  sessionWasCleared,
  signIn,
} from '@/lib/webcore-auth'

export default function Page() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const [email, setEmail] = useState('')
  const [needsEmail, setNeedsEmail] = useState(false)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [avatarUrl, setAvatarUrl] = useState(withBase('/assets/img/avatar/avatar.png'))
  const [displayName, setDisplayName] = useState('')
  const [lockScreenTimeoutSeconds, setLockScreenTimeoutSeconds] = useState(5 * 60)
  const returnTo = searchParams.get('returnTo')
  const destination = returnTo?.startsWith('/') && !returnTo.startsWith('//')
    ? returnTo
    : '/'

  useEffect(() => {
    const cls = ['lockscreen', 'bg-body-secondary']
    document.body.classList.add(...cls)
    return () => document.body.classList.remove(...cls)
  }, [])

  useEffect(() => {
    let active = true
    void getCurrentUser().then(async response => {
      if (!response.ok || !active) return
      const user = await response.json() as { avatarUrl?: unknown; displayName?: unknown; lockScreenTimeoutSeconds?: unknown; LockScreenTimeoutSeconds?: unknown }
      if (!active) return
      if (typeof user.avatarUrl === 'string' && user.avatarUrl.trim()) {
        setAvatarUrl(user.avatarUrl.startsWith('/') ? withBase(user.avatarUrl) : user.avatarUrl)
      }
      if (typeof user.displayName === 'string' && user.displayName.trim()) setDisplayName(user.displayName.trim())
      const configuredSeconds = Number(user.lockScreenTimeoutSeconds ?? user.LockScreenTimeoutSeconds)
      if (Number.isInteger(configuredSeconds) && configuredSeconds > 0 && configuredSeconds <= 86_400) setLockScreenTimeoutSeconds(configuredSeconds)
    }).catch(() => undefined)
    return () => { active = false }
  }, [])

  useEffect(() => {
    const storedEmail =
      window.sessionStorage.getItem(activeUserEmailStorageKey)
      ?? window.localStorage.getItem(rememberedEmailStorageKey)
      ?? ''
    setEmail(storedEmail)
    setNeedsEmail(!storedEmail)
  }, [])

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!email) {
      setNeedsEmail(true)
      setError('Không xác định được tài khoản đang khóa. Vui lòng nhập email hoặc đăng nhập lại.')
      return
    }
    if (!password) return

    setError(undefined)
    setIsSubmitting(true)

    try {
      const rememberMe = window.localStorage.getItem(rememberedEmailStorageKey) === email
      const response = await signIn(email, password, rememberMe)
      if (!response.ok) {
        if (sessionWasCleared(response)) {
          clearClientAuthenticationState()
          setNeedsEmail(true)
          setPassword('')
        }
        setError(await getErrorMessage(response))
        return
      }

      window.localStorage.setItem(authSessionStorageKey, '1')
      window.sessionStorage.setItem(activeUserEmailStorageKey, email)
      router.replace(destination)
      router.refresh()
    } catch {
      setError('Cannot connect to the WebCore API.')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="lockscreen-wrapper">
      <div className="lockscreen-logo">
        <a href="/">
          <b>Web</b>Core
        </a>
      </div>

      <div className="lockscreen-name">{displayName || 'Session locked'}</div>

      <div className="lockscreen-item">
        <div className="lockscreen-image">
          <img src={avatarUrl} alt="User avatar" width={128} height={128} />
        </div>

        <form className="lockscreen-credentials" onSubmit={onSubmit}>
          {needsEmail && <input
            type="text"
            className="form-control mb-2 shadow-none"
            placeholder="username or email"
            autoComplete="username"
            value={email}
            onChange={event => setEmail(event.target.value)}
            disabled={isSubmitting}
            required
          />}
          <div className="input-group">
            <input
              type="password"
              className="form-control shadow-none"
              placeholder="password"
              autoComplete="current-password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              disabled={isSubmitting}
              required
            />
            <div className="input-group-text border-0 bg-transparent px-1">
              <button type="submit" className="btn shadow-none" aria-label="Unlock session" disabled={isSubmitting}>
                <i className="bi bi-box-arrow-right text-body-secondary" />
              </button>
            </div>
          </div>
        </form>
      </div>

      <div className="help-block text-center">Your session was locked after {formatInactivityTimeout(lockScreenTimeoutSeconds)} of inactivity.</div>
      {error && <div className="text-center text-danger mt-2">{error}</div>}
      <div className="text-center">
        <a href="/login" className="text-decoration-none">
          Sign in to continue
        </a>
      </div>
    </div>
  )
}

function formatInactivityTimeout(seconds: number) {
  if (seconds < 60) return `${seconds} second${seconds === 1 ? '' : 's'}`
  if (seconds % 60 === 0) {
    const minutes = seconds / 60
    return `${minutes} minute${minutes === 1 ? '' : 's'}`
  }
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return `${minutes} minute${minutes === 1 ? '' : 's'} ${remainingSeconds} second${remainingSeconds === 1 ? '' : 's'}`
}
