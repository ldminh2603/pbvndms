'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import FileManager, { FileSelectionItem, Item, ItemView, Permissions, Toolbar } from 'devextreme-react/file-manager'
import LoadPanel from 'devextreme-react/load-panel'
import CustomFileSystemProvider from 'devextreme/file_management/custom_provider'
import type FileSystemItem from 'devextreme/file_management/file_system_item'
import type UploadInfo from 'devextreme/file_management/upload_info'
import { AppContent, Toast } from '@adminlte/react'
import { webCoreApi } from '@/lib/webcore-api'

type FileManagerPermissions = { create?: boolean; copy?: boolean; move?: boolean; delete?: boolean; rename?: boolean; upload?: boolean; download?: boolean }
type UploadUi = {
  moduleId: string
  moduleName: string
  configUi?: { toolbar?: { showTemplateDownload?: boolean }; fileManager?: { view?: 'details' | 'thumbnails'; showFolders?: boolean; allowedFileExtensions?: string[]; permissions?: FileManagerPermissions } }
  permission?: { actions?: string[]; operations?: string[]; isPrivilegedAccount?: boolean }
}
type FileItem = { key: string; path: string; name: string; isDirectory: boolean; size?: number; dateModified?: string | null; hasSubDirectories?: boolean }
type UploadFile = { clientFileName: string; uploadObjectKey: string; url: string; uploadMethod?: string; headers?: Record<string, string>; fields?: Record<string, string> }
type UploadSession = { uploadId: string; files: UploadFile[] }
type ToastState = { message: string; theme: 'danger' | 'warning' }

const uploadBase = (moduleId: string) => `/upload/${encodeURIComponent(moduleId)}`
const has = (values: string[] | undefined, value: string) => values?.some(item => item === '*' || item.toLowerCase() === value.toLowerCase()) ?? false

function effectivePermissions(ui: UploadUi | undefined): Required<FileManagerPermissions> {
  const configured = ui?.configUi?.fileManager?.permissions ?? {}
  const privileged = ui?.permission?.isPrivilegedAccount === true
  const canUpload = privileged || has(ui?.permission?.operations, 'Upload')
  const can = (operation: string, enabled: boolean | undefined) => enabled !== false && (privileged || has(ui?.permission?.operations, operation))
  return { create: configured.create !== false && canUpload, upload: configured.upload !== false && canUpload, download: can('Download', configured.download), delete: can('Delete', configured.delete), rename: can('Rename', configured.rename), move: can('Move', configured.move), copy: can('Copy', configured.copy) }
}

function createProvider(moduleId: string, onFailure: (message: string) => void) {
  const sessions = new Map<string, UploadSession>()
  const request = <T,>(path: string, init?: RequestInit) => webCoreApi<T>(`${uploadBase(moduleId)}${path}`, init)
  const json = (method: string, path: string, body: unknown) => request<Record<string, unknown>>(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  const keyOf = (directory: FileSystemItem, file: File) => `${directory.path || ''}/${file.name}`

  return new CustomFileSystemProvider({
    keyExpr: 'key', isDirectoryExpr: 'isDirectory', nameExpr: 'name', sizeExpr: 'size', dateModifiedExpr: 'dateModified', hasSubDirectoriesExpr: 'hasSubDirectories',
    getItems: async parent => {
      try {
        const result = await request<{ items: FileItem[] }>(`/files?path=${encodeURIComponent(parent.path || '')}`)
        return result.items.map(item => ({ ...item, dateModified: item.dateModified ? new Date(item.dateModified) : undefined }))
      } catch (error) {
        // FileManager 22.x does not safely handle a rejected custom-provider
        // promise during initial rendering.  Keep it alive and surface the API
        // error as the normal corner toast instead.
        onFailure(error instanceof Error ? error.message : 'Cannot load files.')
        return []
      }
    },
    createDirectory: async (parent, name) => { await json('POST', '/folders', { parentPath: parent.path || '', name }) },
    renameItem: async (item, newName) => { await json('POST', '/items/rename', { path: item.path, isDirectory: item.isDirectory, newName }) },
    deleteItem: async item => { await json('DELETE', '/files', { items: [{ path: item.path, isDirectory: item.isDirectory }], confirmed: true }) },
    moveItem: async (item, destination) => { await json('POST', '/items/move', { sourcePath: item.path, destinationParentPath: destination.path || '', isDirectory: item.isDirectory, overwrite: false }) },
    copyItem: async (item, destination) => { await json('POST', '/items/copy', { sourcePath: item.path, destinationParentPath: destination.path || '', isDirectory: item.isDirectory, overwrite: false }) },
    downloadItems: async items => {
      for (const item of items.filter(item => !item.isDirectory)) {
        const result = await request<{ url: string }>(`/files/download-url?path=${encodeURIComponent(item.path)}`)
        window.open(result.url, '_blank', 'noopener')
      }
    },
    uploadFileChunk: async (file, info: UploadInfo, destination) => {
      const fileKey = keyOf(destination, file)
      if (info.chunkIndex === 0) {
        const session = await json('POST', '/uploads/init', { targetDirectoryPath: destination.path || '', files: [{ clientFileName: file.name, fileSize: file.size, contentType: file.type || undefined }] }) as unknown as UploadSession
        const target = session.files[0]
        if (!target) throw new Error('Upload session did not include the selected file.')
        sessions.set(fileKey, session)
        if (target.uploadMethod === 'presigned-post' && target.fields) {
          const form = new FormData()
          Object.entries(target.fields).forEach(([name, value]) => form.append(name, value))
          form.append('file', file)
          const response = await fetch(target.url, { method: 'POST', body: form })
          if (!response.ok) throw new Error(`Storage upload failed (${response.status}).`)
        } else {
          const response = await fetch(target.url, { method: 'PUT', body: file, headers: { 'Content-Type': file.type || 'application/octet-stream', ...(target.headers ?? {}) } })
          if (!response.ok) throw new Error(`Storage upload failed (${response.status}).`)
        }
      }
      if (info.chunkIndex === info.chunkCount - 1) {
        const session = sessions.get(fileKey)
        const target = session?.files[0]
        if (!session || !target) throw new Error('Upload session expired before completion.')
        await json('POST', '/uploads/complete', { uploadId: session.uploadId, files: [{ clientFileName: file.name, uploadObjectKey: target.uploadObjectKey }] })
        sessions.delete(fileKey)
      }
    },
    abortFileUpload: async (file, _info, destination) => {
      const session = sessions.get(keyOf(destination, file))
      if (session) {
        await json('POST', '/uploads/abort', { uploadId: session.uploadId, reason: 'User cancelled upload' })
        sessions.delete(keyOf(destination, file))
      }
    },
  })
}

export function RuntimeUploadModule({ moduleId }: { moduleId: string }) {
  const [ui, setUi] = useState<UploadUi>()
  const [loading, setLoading] = useState(true)
  const [toast, setToast] = useState<ToastState>()
  const managerRef = useRef<FileManager>(null)
  const showToast = (message: string, theme: ToastState['theme'] = 'danger') => setToast({ message, theme })
  const onProviderFailure = useCallback((message: string) => showToast(message), [])
  const provider = useMemo(() => createProvider(moduleId, onProviderFailure), [moduleId, onProviderFailure])
  const permissions = effectivePermissions(ui)
  const fileConfig = ui?.configUi?.fileManager

  useEffect(() => {
    let active = true
    setLoading(true); setUi(undefined)
    void webCoreApi<UploadUi>(`${uploadBase(moduleId)}/ui`).then(result => { if (active) setUi(result) }).catch(error => { if (active) showToast(error instanceof Error ? error.message : 'Cannot load upload module.') }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [moduleId])

  const refresh = useCallback(() => managerRef.current?.instance.refresh(), [])
  const template = useCallback(async () => {
    try { const result = await webCoreApi<{ url: string }>(`${uploadBase(moduleId)}/template-url`); window.open(result.url, '_blank', 'noopener') }
    catch (error) { showToast(error instanceof Error ? error.message : 'Cannot download template.') }
  }, [moduleId])

  if (loading) return <LoadPanel visible shading />
  if (!ui) return <AppContent><div className="alert alert-danger mb-0">Unable to load upload module.</div></AppContent>

  return <AppContent>
    <section className="card h-100"><div className="card-header"><h3 className="card-title mb-0">{ui.moduleName}</h3></div><div className="card-body p-2" style={{ minHeight: 650 }}>
      <FileManager ref={managerRef} fileSystemProvider={provider} rootFolderName={ui.moduleName} height="650px" allowedFileExtensions={fileConfig?.allowedFileExtensions} onDirectoryCreated={refresh} onItemDeleted={refresh} onItemRenamed={refresh} onFileUploaded={refresh} onItemMoved={refresh} onItemCopied={refresh}>
        <Permissions {...permissions} /><ItemView mode={fileConfig?.view ?? 'details'} showFolders={fileConfig?.showFolders !== false} />
        <Toolbar><Item name="showNavPane" visible={fileConfig?.showFolders !== false} /><Item name="separator" /><Item name="create" /><Item name="upload" /><Item name="refresh" options={{ onClick: refresh }} />
          {ui.configUi?.toolbar?.showTemplateDownload && <Item name="downloadTemplate" widget="dxButton" options={{ icon: 'download', text: 'Download Template', onClick: template }} />}
          <Item name="separator" location="after" /><Item name="switchView" /><FileSelectionItem name="download" /><FileSelectionItem name="separator" /><FileSelectionItem name="move" /><FileSelectionItem name="copy" /><FileSelectionItem name="rename" /><FileSelectionItem name="separator" /><FileSelectionItem name="delete" /><FileSelectionItem name="refresh" /><FileSelectionItem name="clearSelection" location="after" />
        </Toolbar>
      </FileManager>
    </div></section>
    <div className="toast-container position-fixed bottom-0 end-0 p-3" style={{ zIndex: 2000 }}><Toast show={Boolean(toast)} autohideMs={6000} onClose={() => setToast(undefined)} theme={toast?.theme} title={toast?.theme === 'warning' ? 'Warning' : 'Error'}>{toast?.message ?? ''}</Toast></div>
  </AppContent>
}
