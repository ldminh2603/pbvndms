import { DemoLayout } from '@/components/demo-layout'

/** Shared shell for static modules and metadata-driven module screens. */
export default function AppShellLayout({ children }: { children: React.ReactNode }) {
  return <DemoLayout fixedHeader fixedSidebar>{children}</DemoLayout>
}
