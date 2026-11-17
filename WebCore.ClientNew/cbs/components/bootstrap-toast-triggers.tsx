'use client'

import { useEffect } from 'react'

type BootstrapToast = { show: () => void }

declare global {
  interface Window {
    bootstrap?: {
      Toast?: {
        getOrCreateInstance: (element: Element) => BootstrapToast
      }
    }
  }
}

/** Bootstrap Toast has no declarative data-API trigger, unlike collapse/dropdown. */
export function BootstrapToastTriggers() {
  useEffect(() => {
    const onClick = (event: MouseEvent) => {
      const trigger = (event.target as Element | null)?.closest<HTMLElement>('[data-bs-toggle="toast"]')
      const selector = trigger?.getAttribute('data-bs-target')
      if (!selector) return

      const toast = document.querySelector(selector)
      if (!toast) return

      window.bootstrap?.Toast?.getOrCreateInstance(toast).show()
    }

    document.addEventListener('click', onClick)
    return () => document.removeEventListener('click', onClick)
  }, [])

  return null
}
