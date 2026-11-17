export default function AppShellLoading() {
  return (
    <main className="dashboard-route-loading" aria-busy="true" aria-live="polite">
      <div className="spinner-border text-primary" role="status">
        <span className="visually-hidden">Đang tải màn hình…</span>
      </div>
      <span>Đang tải màn hình…</span>
    </main>
  )
}
