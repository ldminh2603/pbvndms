import { webCoreApi } from './webcore-api'

export const webCoreLanguageStorageKey = 'preferred_language'
export const webCoreLanguageChangedEvent = 'webcore-language-changed'
export type LanguageOption = { code: string; label: string }
type ApiRecord = Record<string, unknown>

const languageSelectorModuleId = 'MAINN'
const languageSelectorSubModule = 'MMN'

export function normalizeLanguageCode(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim().toUpperCase() : undefined
}

const apiValue = (record: ApiRecord, name: string): unknown => {
  const key = Object.keys(record).find(candidate => candidate.toLowerCase() === name.toLowerCase())
  return key ? record[key] : undefined
}

/**
 * The language selector is configured by DEFMODFLD.LANGID on module MAINN/MMN.
 * The form-info cache expands its CCB list source to ListSourceItems from DEFCODE.
 */
export function languageOptionsFromFormInfo(formInfo: unknown): LanguageOption[] {
  if (!formInfo || typeof formInfo !== 'object') return []

  const form = formInfo as ApiRecord
  // Field groups vary with the module type. Inspect every top-level field
  // collection so LANGID is found whether it is Common, Layout, Condition, etc.
  const fields = Object.values(form).flatMap(value => Array.isArray(value) ? value : [])
  const languageField = fields.find(value => {
    if (!value || typeof value !== 'object') return false
    const field = value as ApiRecord
    const fieldName = apiValue(field, 'fieldName')
    return typeof fieldName === 'string' && fieldName.trim().toUpperCase() === 'LANGID'
  })
  if (!languageField || typeof languageField !== 'object') return []

  const values = apiValue(languageField as ApiRecord, 'listSourceItems')
  if (!Array.isArray(values)) return []

  const seen = new Set<string>()
  return values.flatMap(value => {
    const item = value && typeof value === 'object' ? value as ApiRecord : {}
    const code = normalizeLanguageCode(apiValue(item, 'value'))
    if (!code || seen.has(code)) return []
    seen.add(code)
    const text = apiValue(item, 'text')
    const codeValueName = apiValue(item, 'codeValueName')
    const label = typeof text === 'string' && text.trim()
      ? text.trim()
      : typeof codeValueName === 'string' && codeValueName.trim()
        ? codeValueName.trim()
        : code
    return [{ code, label }]
  })
}

/** DEFMOD/DEFMODFLD metadata for the post-login main shell. */
export async function loadLanguageOptions(): Promise<LanguageOption[]> {
  const formInfo = await webCoreApi<unknown>(
    `/module/${languageSelectorModuleId}/form-info/${languageSelectorSubModule}`,
  )
  return languageOptionsFromFormInfo(formInfo)
}

/** Localized labels for DEFMODFLD.LANGID, keyed by its Value. */
export async function loadLanguageLookup(language: string): Promise<Record<string, string>> {
  const localization = await webCoreApi<unknown>(
    `/module/${languageSelectorModuleId}/${languageSelectorSubModule}/localization`,
    { headers: { 'Accept-Language': language } },
  )
  if (!localization || typeof localization !== 'object') return {}

  const lookups = apiValue(localization as ApiRecord, 'lookups')
  if (!lookups || typeof lookups !== 'object') return {}
  const lookupKey = Object.keys(lookups as ApiRecord)
    .find(key => key.toUpperCase() === 'LANGID')
  const values = lookupKey ? (lookups as ApiRecord)[lookupKey] : undefined
  if (!values || typeof values !== 'object') return {}

  return Object.entries(values as ApiRecord).reduce<Record<string, string>>((result, [code, text]) => {
    const normalizedCode = normalizeLanguageCode(code)
    if (normalizedCode && typeof text === 'string' && text.trim()) result[normalizedCode] = text.trim()
    return result
  }, {})
}

export function withLocalizedLanguageLabels(options: LanguageOption[], lookup: Record<string, string>): LanguageOption[] {
  return options.map(option => ({ ...option, label: lookup[option.code] ?? option.label }))
}

/** Matches the legacy client's DEFLANG user-setting contract. */
export function languageFromUserSetting(user: unknown): string | undefined {
  if (!user || typeof user !== 'object') return undefined
  const record = user as Record<string, unknown>
  const setting = record.setting ?? record.Setting
  let parsed: Record<string, unknown> | undefined
  if (typeof setting === 'string') {
    try {
      const value = JSON.parse(setting)
      parsed = value && typeof value === 'object' ? value as Record<string, unknown> : undefined
    } catch { parsed = undefined }
  } else if (setting && typeof setting === 'object') parsed = setting as Record<string, unknown>
  return normalizeLanguageCode(record.languageId ?? record.LanguageId ?? record.preferredLanguage ?? record.PreferredLanguage
    ?? parsed?.langId ?? parsed?.languageId ?? parsed?.language)
}

export function resolveLanguage(options: LanguageOption[], ...candidates: Array<string | undefined>): string | undefined {
  const available = new Set(options.map(option => option.code))
  return candidates.map(normalizeLanguageCode).find((code): code is string => Boolean(code && available.has(code)))
    ?? options[0]?.code
}

export function readStoredLanguage(): string | undefined {
  return typeof window === 'undefined' ? undefined : normalizeLanguageCode(window.localStorage.getItem(webCoreLanguageStorageKey))
}

export function persistLanguage(language: string) {
  window.localStorage.setItem(webCoreLanguageStorageKey, language)
  window.dispatchEvent(new Event(webCoreLanguageChangedEvent))
}
