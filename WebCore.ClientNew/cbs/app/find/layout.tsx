import { DemoLayout } from '@/components/demo-layout'

/** Find routes are outside the dashboard group but retain the application menu. */
export default function FindLayout({ children }: { children: React.ReactNode }) {
  return <DemoLayout fixedHeader fixedSidebar>{children}</DemoLayout>
}
