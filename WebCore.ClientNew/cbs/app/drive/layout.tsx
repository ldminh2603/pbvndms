import { DemoLayout } from '@/components/demo-layout'

export default function DriveLayout({ children }: { children: React.ReactNode }) {
  return <DemoLayout fixedHeader fixedSidebar>{children}</DemoLayout>
}
