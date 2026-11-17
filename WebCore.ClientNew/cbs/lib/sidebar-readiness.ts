'use client'

import { createContext, useContext } from 'react'

/** True only after the active component's sidebar menu has finished loading. */
export const SidebarReadyContext = createContext(false)

export function useSidebarReady() {
  return useContext(SidebarReadyContext)
}
