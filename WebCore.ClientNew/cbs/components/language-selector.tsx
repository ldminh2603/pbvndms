'use client'

import { useEffect, useState } from 'react'
import { getCurrentUser } from '@/lib/webcore-auth'
import { webCoreApi } from '@/lib/webcore-api'
import {
  languageFromUserSetting,
  loadLanguageLookup,
  loadLanguageOptions,
  persistLanguage,
  readStoredLanguage,
  resolveLanguage,
  type LanguageOption,
  withLocalizedLanguageLabels,
} from '@/lib/language'

export { webCoreLanguageChangedEvent, webCoreLanguageStorageKey } from '@/lib/language'

function flagSource(language: string) {
  return `/assets/flags/${language === 'EN' ? 'gb' : language.toLowerCase()}.svg`
}

export function LanguageSelector() {
  const [languages, setLanguages] = useState<LanguageOption[]>([])
  const [language, setLanguage] = useState<string>()

  // Local storage controls immediate browser rendering, while the runtime
  // commit services resolve DEFLANG from the authenticated user's persisted
  // LANGID. Keep both stores in sync so a success message has the same
  // language as the flag currently shown in the shell.
  const saveLanguage = (nextLanguage: string) => {
    persistLanguage(nextLanguage)
    return webCoreApi('/user-config/language', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value: nextLanguage }),
    })
  }

  useEffect(() => {
    let active = true
    void Promise.all([loadLanguageOptions(), getCurrentUser()]).then(async ([options, userResponse]) => {
      if (!active) return
      const user = userResponse.ok ? await userResponse.json() : undefined
      if (!active) return
      const selected = resolveLanguage(options, readStoredLanguage(), languageFromUserSetting(user))
      setLanguage(selected)
      // The locally selected language may be newer than USER_CONFIG (for
      // example when a user changes the flag in another browser tab). Always
      // persist the resolved choice for server-side success messages.
      if (selected) void saveLanguage(selected).catch(() => {})
      const lookup = selected ? await loadLanguageLookup(selected).catch(() => ({})) : {}
      if (!active) return
      setLanguages(withLocalizedLanguageLabels(options, lookup))
    }).catch(() => {
      if (active) setLanguage(readStoredLanguage())
    })
    return () => { active = false }
  }, [])

  if (!language || languages.length === 0) return null

  return (
    <li className="nav-item dropdown">
      <button id="webcore-language" type="button" className="nav-link d-flex align-items-center gap-1"
        data-bs-toggle="dropdown" aria-expanded="false" aria-label="Choose language">
        <img src={flagSource(language)} alt="" width="22" height="16" />
      </button>
      <ul className="dropdown-menu dropdown-menu-end" aria-labelledby="webcore-language">
        {languages.map(item => (
          <li key={item.code}>
            <button type="button" className={`dropdown-item d-flex align-items-center ${language === item.code ? 'active' : ''}`}
              onClick={() => {
                void saveLanguage(item.code).catch(() => {})
                setLanguage(item.code)
                void loadLanguageLookup(item.code)
                  .then(lookup => setLanguages(current => withLocalizedLanguageLabels(current, lookup)))
                  .catch(() => {})
              }}>
              <img className="me-2" src={flagSource(item.code)} alt="" width="22" height="16" />
              {item.label}
              {language === item.code && <i className="bi bi-check-lg ms-auto" />}
            </button>
          </li>
        ))}
      </ul>
    </li>
  )
}
