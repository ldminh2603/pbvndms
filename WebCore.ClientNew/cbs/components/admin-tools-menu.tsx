"use client"

import { useEffect, useState } from "react"
import { createPortal } from "react-dom"
import { Modal, Toast } from "@adminlte/react"
import { useRouter } from "next/navigation"
import { getCurrentUser } from "@/lib/webcore-auth"
import { webCoreApi } from "@/lib/webcore-api"

type ToastState = {
  message: string
  theme: "success" | "danger"
}

type CurrentUser = {
  isPrivilegedAccount?: boolean
  IsPrivilegedAccount?: boolean
}

type RuntimeLogLevel = "Information" | "Debug" | "Trace"

type RuntimeLogLevelState = {
  message?: string
  configuredMinimumLevel: RuntimeLogLevel
  effectiveMinimumLevel: RuntimeLogLevel
  runtimeOverrideMinimumLevel?: RuntimeLogLevel
  runtimeOverrideExpiresAtUtc?: string
  runtimeOverrideActive: boolean
  debugTimeoutMinutes: number
  traceTimeoutMinutes: number
}

function isPrivilegedAccount(user: CurrentUser) {
  return user.isPrivilegedAccount === true || user.IsPrivilegedAccount === true
}

export function AdminToolsMenu() {
  const router = useRouter()
  const [isPrivileged, setIsPrivileged] = useState(false)
  const [logLevelState, setLogLevelState] = useState<RuntimeLogLevelState | undefined>()
  const [isSaving, setIsSaving] = useState(false)
  const [toast, setToast] = useState<ToastState | undefined>()
  const [pendingReload, setPendingReload] = useState<"core" | "role">("core")
  const [portalTarget, setPortalTarget] = useState<HTMLElement | null>(null)

  useEffect(() => {
    let alive = true
    let retryTimer: number | undefined
    let didRetry = false

    const loadPrivileges = () => {
      void getCurrentUser()
        .then(async (response) => {
          if (!response.ok || response.status === 204) {
            if (alive && !didRetry) {
              didRetry = true
              retryTimer = window.setTimeout(loadPrivileges, 500)
            }
            return
          }

          const user = await response.json() as CurrentUser
          if (!alive || !isPrivilegedAccount(user)) return

          setIsPrivileged(true)
          const result = await webCoreApi<RuntimeLogLevelState>("/admin/tools/log-level")
          if (alive && result) setLogLevelState(result)
        })
        .catch(() => {
          if (alive && !didRetry) {
            didRetry = true
            retryTimer = window.setTimeout(loadPrivileges, 500)
          }
        })
    }

    loadPrivileges()

    return () => {
      alive = false
      if (retryTimer) window.clearTimeout(retryTimer)
    }
  }, [])

  useEffect(() => {
    setPortalTarget(document.body)
  }, [])

  useEffect(() => {
    const expiresAtUtc = logLevelState?.runtimeOverrideExpiresAtUtc
    if (!expiresAtUtc) return

    const delayMs = new Date(expiresAtUtc).getTime() - Date.now() + 1000
    if (!Number.isFinite(delayMs)) return

    const timer = window.setTimeout(() => {
      void webCoreApi<RuntimeLogLevelState>("/admin/tools/log-level").then(setLogLevelState).catch(() => undefined)
    }, Math.max(0, delayMs))
    return () => window.clearTimeout(timer)
  }, [logLevelState?.runtimeOverrideExpiresAtUtc])

  const notify = (message: string, theme: ToastState["theme"]) => {
    setToast({ message, theme })
  }

  const updateLogLevel = async (mode: RuntimeLogLevel) => {
    setIsSaving(true)
    try {
      const result = await webCoreApi<RuntimeLogLevelState>("/admin/tools/log-level", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mode }),
      })
      setLogLevelState(result)
      notify(result.message ?? "Da cap nhat muc log runtime.", "success")
    } catch (error) {
      notify(error instanceof Error ? error.message : "Khong the cap nhat muc log.", "danger")
    } finally {
      setIsSaving(false)
    }
  }

  const reload = async (kind: "core" | "role") => {
    const label = kind === "core" ? "core" : "role"
    setIsSaving(true)
    try {
      const result = await webCoreApi<{ message?: string }>(`/admin/tools/reload-${kind}`)
      notify(result?.message ?? `Reload ${label} thanh cong.`, "success")
    } catch (error) {
      notify(error instanceof Error ? error.message : `Reload ${label} that bai.`, "danger")
    } finally {
      setIsSaving(false)
    }
  }

  const askReload = (kind: "core" | "role") => {
    setPendingReload(kind)
  }

  if (!isPrivileged) return null

  const effectiveLogLevel = logLevelState?.effectiveMinimumLevel ?? "Information"
  const runtimeOverrideMode = logLevelState?.runtimeOverrideActive
    ? logLevelState.runtimeOverrideMinimumLevel
    : undefined
  const debugEnabled = runtimeOverrideMode === "Debug" || runtimeOverrideMode === "Trace"
  const traceEnabled = runtimeOverrideMode === "Trace"
  const debugTimeoutMinutes = logLevelState?.debugTimeoutMinutes ?? 30
  const traceTimeoutMinutes = logLevelState?.traceTimeoutMinutes ?? 15

  const toggleDebug = () => {
    void updateLogLevel(debugEnabled ? "Information" : (traceEnabled ? "Trace" : "Debug"))
  }

  const toggleTrace = () => {
    void updateLogLevel(traceEnabled ? "Debug" : "Trace")
  }

  return (
    <>
      <li className="nav-item dropdown">
        <button
          aria-label="Admin tools"
          className="nav-link border-0 bg-transparent"
          data-bs-auto-close="false"
          data-bs-toggle="dropdown"
          type="button"
        >
          <i className="bi bi-gear-fill" />
        </button>

        <div className="dropdown-menu dropdown-menu-end admin-tools-menu shadow">
          <div className="px-3 py-2 border-bottom">
            <div className="fw-semibold">Admin tools</div>
            <small className="text-muted">Privileged actions, system status, and special screens</small>
          </div>

          <div className="px-3 py-2 border-bottom small">
            <div className="fw-semibold mb-1">Runtime log level</div>
            <div className="text-muted mb-1">Muc hieu luc: {effectiveLogLevel}</div>
            <div className="text-muted mb-2">Muc cau hinh: {logLevelState?.configuredMinimumLevel ?? "Information"}</div>
            <div className="admin-tools-log-toggle">
              <div><span className="d-block text-dark">Debug</span><small className="text-muted">Tu tat sau {debugTimeoutMinutes} phut</small></div>
              <button aria-checked={debugEnabled} aria-label="Bat Debug" className="admin-tools-log-switch" disabled={isSaving} onClick={toggleDebug} role="switch" type="button">
                <span />
              </button>
            </div>
            <div className="admin-tools-log-toggle mt-2">
              <div><span className="d-block text-dark">Trace</span><small className="text-muted">Tu tat sau {traceTimeoutMinutes} phut</small></div>
              <button aria-checked={traceEnabled} aria-label="Bat Trace" className="admin-tools-log-switch" disabled={isSaving} onClick={toggleTrace} role="switch" type="button">
                <span />
              </button>
            </div>
          </div>

          <div className="p-3 d-grid gap-2">
            <button className="btn btn-primary btn-sm" data-bs-target="#admin-reload-confirm" data-bs-toggle="modal" disabled={isSaving} onClick={() => askReload("core")} type="button"><i className="bi bi-arrow-repeat me-1" /> RELOAD CORE</button>
            <button className="btn btn-primary btn-sm" data-bs-target="#admin-reload-confirm" data-bs-toggle="modal" disabled={isSaving} onClick={() => askReload("role")} type="button"><i className="bi bi-people-fill me-1" /> RELOAD ROLE</button>
            <button className="btn btn-warning btn-sm" onClick={() => router.push("/admin/cluster")} type="button"><i className="bi bi-wrench-adjustable me-1" /> QUAN LY CLUSTER</button>
            <button className="btn btn-outline-secondary btn-sm" onClick={() => router.push("/admin/module-package")} type="button"><i className="bi bi-box-arrow-in-down me-1" /> IMPORT/EXPORT MODULE</button>
            <button className="btn btn-outline-secondary btn-sm" onClick={() => router.push("/admin/log-viewer")} type="button"><i className="bi bi-file-earmark-text me-1" /> OPEN VIEW LOG</button>
            <button className="btn btn-outline-secondary btn-sm" onClick={() => router.push("/admin/tools")} type="button"><i className="bi bi-tools me-1" /> OPEN ADMIN TOOLS</button>
          </div>
        </div>
      </li>

      {portalTarget && createPortal(<Modal
        centered
        footer={
          <>
            <button className="btn btn-light" data-bs-dismiss="modal" type="button">Hủy</button>
            <button className="btn btn-primary" data-bs-dismiss="modal" disabled={isSaving} onClick={() => void reload(pendingReload)} type="button">
              <i className="bi bi-arrow-repeat me-1" /> Reload ngay
            </button>
          </>
        }
        id="admin-reload-confirm"
        title={`Xác nhận reload ${pendingReload}`}
      >
        <div className="d-flex gap-3 align-items-start">
          <i className="bi bi-exclamation-triangle-fill text-warning fs-3" />
          <div>
            <p className="mb-1">Bạn có chắc muốn reload {pendingReload} ngay bây giờ không?</p>
            <small className="text-muted">Thao tác này sẽ làm mới dữ liệu runtime đang được nạp.</small>
          </div>
        </div>
      </Modal>, portalTarget)}

      <div className="toast-container position-fixed bottom-0 end-0 p-3">
        <Toast
          autohideMs={5000}
          onClose={() => setToast(undefined)}
          show={Boolean(toast)}
          theme={toast?.theme}
          title={toast?.theme === "success" ? "Thanh cong" : "Thao tac that bai"}
        >
          {toast?.message ?? ""}
        </Toast>
      </div>
    </>
  )
}
