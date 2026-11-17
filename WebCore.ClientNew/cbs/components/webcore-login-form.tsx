'use client'

import { useEffect, useState, type FormEvent } from 'react'
import { useRouter } from 'next/navigation'
import { Toast } from '@adminlte/react'
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

export type LoginLocalization = {
  TitleDefault?: unknown
  TitleInfo?: unknown
  Fields?: Record<string, unknown>
  FieldHiden?: Record<string, unknown>
  Buttons?: Record<string, unknown>
}

function localizedText(name: string, values: Record<string, unknown>): string | undefined {
  const requestedName = name.trim().toUpperCase()
  const match = Object.entries(values).find(([key]) => key.trim().toUpperCase() === requestedName)
  const text = match?.[1]
  return typeof text === 'string' && text.trim() ? text.trim() : undefined
}

export function WebCoreLoginForm({ localization }: { localization?: LoginLocalization }) {
  const router = useRouter()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [rememberMe, setRememberMe] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string>()

  useEffect(() => {
    const rememberedEmail = window.localStorage.getItem(rememberedEmailStorageKey)
    if (rememberedEmail) {
      setEmail(rememberedEmail)
      setRememberMe(true)
    }
  }, [])

  const usernameText = localizedText('username', localization?.FieldHiden ?? {}) ?? 'Email'
  const passwordText = localizedText('password', localization?.FieldHiden ?? {}) ?? 'Password'
  const rememberText = localizedText('remember', localization?.Fields ?? {}) ?? 'Remember Me'
  const loginText = localizedText('btn_login', localization?.Buttons ?? {}) ?? 'Sign In'

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    if (!form.checkValidity()) {
      form.classList.add('was-validated')
      return
    }

    setIsSubmitting(true)
    setError(undefined)
    try {
      const loginResponse = await signIn(email, password, rememberMe)
      if (!loginResponse.ok) {
        if (sessionWasCleared(loginResponse)) {
          clearClientAuthenticationState()
          setPassword('')
        }
        setError(await getErrorMessage(loginResponse))
        return
      }

      const userResponse = await getCurrentUser()
      if (!userResponse.ok) {
        setError('Signed in, but failed to load the user session.')
        return
      }

      window.localStorage.setItem(authSessionStorageKey, '1')
      window.sessionStorage.setItem(activeUserEmailStorageKey, email)
      if (rememberMe) window.localStorage.setItem(rememberedEmailStorageKey, email)
      else window.localStorage.removeItem(rememberedEmailStorageKey)
      router.replace('/component')
      router.refresh()
    } catch {
      setError('Cannot connect to the WebCore API.')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <>
      <form className="needs-validation" noValidate onSubmit={onSubmit}>
        <div className="input-group mb-3 has-validation">
          <input name="username" type="text" required className="form-control" placeholder={usernameText} aria-label={usernameText} autoComplete="username" value={email} onChange={event => setEmail(event.target.value)} disabled={isSubmitting} />
          <div className="input-group-text"><span className="bi bi-envelope" /></div>
          <div className="invalid-feedback">Please enter your username or email address.</div>
        </div>
        <div className="input-group mb-3 has-validation">
          <div className="webcore-password-field">
            <input name="password" type={showPassword ? 'text' : 'password'} required className="form-control" placeholder={passwordText} aria-label={passwordText} autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} disabled={isSubmitting} />
            <button type="button" className="webcore-password-toggle" aria-label={showPassword ? 'Hide password' : 'Show password'} title={showPassword ? 'Hide password' : 'Show password'} onClick={() => setShowPassword(visible => !visible)} disabled={isSubmitting}>
              <span className={showPassword ? 'bi bi-eye-slash' : 'bi bi-eye'} />
            </button>
          </div>
          <div className="input-group-text"><span className="bi bi-lock-fill" /></div>
          <div className="invalid-feedback">Password is required.</div>
        </div>
        <div className="form-check mb-3">
          <input id="remember-me" name="remember" className="form-check-input" type="checkbox" checked={rememberMe} onChange={event => setRememberMe(event.target.checked)} disabled={isSubmitting} />
          <label className="form-check-label" htmlFor="remember-me">{rememberText}</label>
        </div>
        <div className="d-grid gap-2">
          <button name="btn_login" type="submit" className="btn btn-primary" disabled={isSubmitting}>
            {isSubmitting ? 'Signing in...' : loginText}
          </button>
        </div>
      </form>
      <div className="toast-container position-fixed bottom-0 end-0 p-3">
        <Toast show={Boolean(error)} onClose={() => setError(undefined)} theme="danger" title="Sign in failed">
          {error}
        </Toast>
      </div>
    </>
  )
}
