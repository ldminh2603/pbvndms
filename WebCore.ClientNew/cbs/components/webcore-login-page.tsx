'use client'

import { useEffect, useState } from 'react'
import { AuthLayout } from '@adminlte/react'
import { type LoginLocalization, WebCoreLoginForm } from '@/components/webcore-login-form'
import { webCoreApi } from '@/lib/webcore-api'

const defaultTitle = 'WebCore'
const defaultInfo = 'Sign in to your WebCore session'

function text(value: unknown, fallback: string) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}

export function WebCoreLoginPage() {
  const [localization, setLocalization] = useState<LoginLocalization>()

  useEffect(() => {
    let active = true
    const language = (window.localStorage.getItem('preferred_language') || 'VN').trim().toUpperCase()
    const headers = { 'Accept-Language': language }

    void Promise.allSettled([
      webCoreApi<unknown>('/module/forminfo/LOGIN/MMN', { headers }),
      webCoreApi<LoginLocalization>('/module/localizationna/LOGIN/MMN', { headers }),
    ]).then((results) => {
      const localized = results[1]
      if (active && localized.status === 'fulfilled') {
        setLocalization(localized.value)
      }
    })

    return () => {
      active = false
    }
  }, [])

  return (
    <AuthLayout authType="login" logo={text(localization?.TitleDefault, defaultTitle)} logoHref="/">
      <p className="login-box-msg">{text(localization?.TitleInfo, defaultInfo)}</p>
      <WebCoreLoginForm localization={localization} />
    </AuthLayout>
  )
}
