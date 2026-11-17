import { DemoLayout } from '@/components/demo-layout'

export default function UploadLayout({ children }: { children: React.ReactNode }) {
  return <DemoLayout fixedHeader fixedSidebar>{children}</DemoLayout>
}
