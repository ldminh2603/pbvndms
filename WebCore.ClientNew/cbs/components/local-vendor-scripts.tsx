'use client'
import { useEffect } from 'react'
export function LocalVendorScripts() {
  useEffect(() => {
    void import('bootstrap').then((bootstrap) => {
      window.bootstrap = bootstrap
    })
    void import('overlayscrollbars')
  }, [])
  return null
}
